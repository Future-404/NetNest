package com.pwa.shell.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pwa.shell.MainActivity
import com.pwa.shell.R
import com.pwa.shell.data.local.PwaEntity
import org.json.JSONObject
import java.net.IDN
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

enum class PwaNotificationPermission(val webValue: String) {
    DEFAULT("default"),
    GRANTED("granted"),
    DENIED("denied")
}

data class PwaNotificationPermissionRequest(
    val requestId: String,
    val origin: String
)

internal class PwaNotificationPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun get(pwaId: Long, pwaUrl: String): PwaNotificationPermission {
        val expectedOrigin = notificationOrigin(pwaUrl) ?: return PwaNotificationPermission.DENIED
        if (preferences.getString(originKey(pwaId), null) != expectedOrigin) {
            return PwaNotificationPermission.DEFAULT
        }
        return runCatching {
            PwaNotificationPermission.valueOf(
                preferences.getString(stateKey(pwaId), null).orEmpty()
            )
        }.getOrDefault(PwaNotificationPermission.DEFAULT)
    }

    fun set(pwaId: Long, pwaUrl: String, permission: PwaNotificationPermission) {
        val origin = notificationOrigin(pwaUrl) ?: return
        preferences.edit()
            .putString(originKey(pwaId), origin)
            .putString(stateKey(pwaId), permission.name)
            .apply()
    }

    fun clearPermission(pwaId: Long) {
        preferences.edit()
            .remove(originKey(pwaId))
            .remove(stateKey(pwaId))
            .apply()
    }

    fun recordNotificationId(pwaId: Long, notificationId: Int) {
        val ids = preferences.getStringSet(idsKey(pwaId), emptySet())
            .orEmpty()
            .toMutableSet()
        if (ids.size >= MAX_TRACKED_NOTIFICATION_IDS) {
            ids.firstOrNull()?.let(ids::remove)
        }
        ids += notificationId.toString()
        preferences.edit().putStringSet(idsKey(pwaId), ids).apply()
    }

    fun notificationIds(pwaId: Long): Set<Int> =
        preferences.getStringSet(idsKey(pwaId), emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()

    @Synchronized
    fun nextNotificationId(pwaId: Long): Int {
        val current = preferences.getInt(counterKey(pwaId), FIRST_NOTIFICATION_ID)
        val next = if (current == Int.MAX_VALUE) FIRST_NOTIFICATION_ID else current + 1
        preferences.edit().putInt(counterKey(pwaId), next).apply()
        return next
    }

    fun clearAll(pwaId: Long) {
        preferences.edit()
            .remove(originKey(pwaId))
            .remove(stateKey(pwaId))
            .remove(idsKey(pwaId))
            .remove(counterKey(pwaId))
            .apply()
    }

    private fun originKey(pwaId: Long) = "pwa_${pwaId}_origin"
    private fun stateKey(pwaId: Long) = "pwa_${pwaId}_permission"
    private fun idsKey(pwaId: Long) = "pwa_${pwaId}_notification_ids"
    private fun counterKey(pwaId: Long) = "pwa_${pwaId}_notification_counter"

    private companion object {
        const val PREFERENCES_NAME = "pwa_notification_permissions"
        const val MAX_TRACKED_NOTIFICATION_IDS = 100
        const val FIRST_NOTIFICATION_ID = 10_000
    }
}

internal class NotificationRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPerMinute: Int = 5,
    private val maxPerHour: Int = 30
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (timestamps.peekLast()?.let { it > now } == true) timestamps.clear()
        while (timestamps.peekFirst()?.let { it <= now - HOUR_MILLIS } == true) {
            timestamps.removeFirst()
        }
        val minuteCount = timestamps.count { it > now - MINUTE_MILLIS }
        if (minuteCount >= maxPerMinute || timestamps.size >= maxPerHour) return false
        timestamps.addLast(now)
        return true
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 3_600_000L
    }
}

internal class PwaNotificationBridge(
    private val context: Context,
    private val pwa: PwaEntity,
    capabilityToken: String,
    private val permissionStore: PwaNotificationPermissionStore,
    private val evaluateJavascript: (String) -> Unit,
    private val onPermissionRequested: (PwaNotificationPermissionRequest) -> Unit
) {
    private val capabilityTokenBytes = capabilityToken.toByteArray(Charsets.UTF_8)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateLimiter = NotificationRateLimiter()
    private val ownedNotificationIds = mutableSetOf<Int>()
    private val permissionRequestInFlight = AtomicBoolean(false)
    @Volatile private var nextPermissionPromptAt = 0L

    @JavascriptInterface
    fun getPermission(token: String, sourceUrl: String): String {
        if (!isAuthorized(token) || !isNotificationSourceAllowed(sourceUrl, pwa.url)) {
            return PwaNotificationPermission.DENIED.webValue
        }
        return effectiveNotificationPermission(
            context,
            permissionStore.get(pwa.id, pwa.url),
            pwa.id
        ).webValue
    }

    @JavascriptInterface
    fun requestPermission(token: String, requestId: String, sourceUrl: String) {
        if (!isAuthorized(token) || !isValidRequestId(requestId)) return
        val origin = notificationOrigin(sourceUrl)
        if (origin == null || !isNotificationSourceAllowed(sourceUrl, pwa.url)) {
            resolvePermission(requestId, PwaNotificationPermission.DENIED)
            return
        }

        val current = permissionStore.get(pwa.id, pwa.url)
        if (current != PwaNotificationPermission.DEFAULT) {
            resolvePermission(
                requestId,
                effectiveNotificationPermission(context, current, pwa.id)
            )
            return
        }
        if (System.currentTimeMillis() < nextPermissionPromptAt) {
            sendPermissionResult(requestId, PwaNotificationPermission.DEFAULT)
            return
        }
        if (!permissionRequestInFlight.compareAndSet(false, true)) {
            sendPermissionResult(requestId, PwaNotificationPermission.DEFAULT)
            return
        }
        mainHandler.post {
            onPermissionRequested(PwaNotificationPermissionRequest(requestId, origin))
        }
    }

    @JavascriptInterface
    fun show(
        token: String,
        sourceUrl: String,
        title: String,
        body: String,
        tag: String
    ): String {
        if (!isAuthorized(token) || !isNotificationSourceAllowed(sourceUrl, pwa.url)) {
            return RESULT_DENIED
        }
        if (
            effectiveNotificationPermission(
                context,
                permissionStore.get(pwa.id, pwa.url),
                pwa.id
            ) != PwaNotificationPermission.GRANTED
        ) {
            return RESULT_DENIED
        }
        if (!rateLimiter.tryAcquire()) return RESULT_RATE_LIMITED

        val notificationId = notificationIdFor(
            pwaId = pwa.id,
            tag = sanitizeNotificationTag(tag),
            nextUntaggedId = { permissionStore.nextNotificationId(pwa.id) }
        )
        return if (
            publishPwaNotification(
                context = context,
                pwa = pwa,
                notificationId = notificationId,
                title = sanitizeNotificationText(title, MAX_TITLE_LENGTH).ifBlank { pwa.name },
                body = sanitizeNotificationText(body, MAX_BODY_LENGTH)
            )
        ) {
            synchronized(ownedNotificationIds) { ownedNotificationIds += notificationId }
            permissionStore.recordNotificationId(pwa.id, notificationId)
            "$RESULT_OK_PREFIX$notificationId"
        } else {
            RESULT_SYSTEM_DISABLED
        }
    }

    @JavascriptInterface
    fun cancel(token: String, notificationId: String) {
        if (!isAuthorized(token)) return
        val parsedId = notificationId.toIntOrNull() ?: return
        val owned = synchronized(ownedNotificationIds) { parsedId in ownedNotificationIds }
        if (owned) {
            NotificationManagerCompat.from(context).cancel(notificationTag(pwa.id), parsedId)
        }
    }

    fun resolvePermission(
        requestId: String,
        permission: PwaNotificationPermission
    ) {
        if (!isValidRequestId(requestId)) return
        permissionRequestInFlight.set(false)
        if (permission == PwaNotificationPermission.DEFAULT) {
            nextPermissionPromptAt = System.currentTimeMillis() + PERMISSION_PROMPT_COOLDOWN_MILLIS
        }
        sendPermissionResult(requestId, permission)
    }

    private fun sendPermissionResult(
        requestId: String,
        permission: PwaNotificationPermission
    ) {
        val script = "window.__netnestNotificationResolve && " +
            "window.__netnestNotificationResolve(" +
            "${JSONObject.quote(requestId)},${JSONObject.quote(permission.webValue)});"
        mainHandler.post { evaluateJavascript(script) }
    }

    private fun isAuthorized(token: String): Boolean =
        MessageDigest.isEqual(
            capabilityTokenBytes,
            token.toByteArray(Charsets.UTF_8)
        )

    private fun isValidRequestId(requestId: String): Boolean =
        requestId.length in 1..80 && requestId.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }

    private companion object {
        const val RESULT_OK_PREFIX = "OK:"
        const val RESULT_DENIED = "DENIED"
        const val RESULT_RATE_LIMITED = "RATE_LIMITED"
        const val RESULT_SYSTEM_DISABLED = "SYSTEM_DISABLED"
        const val MAX_TITLE_LENGTH = 120
        const val MAX_BODY_LENGTH = 512
        const val PERMISSION_PROMPT_COOLDOWN_MILLIS = 60_000L
    }
}

internal fun notificationOrigin(url: String): String? {
    val parsedUrl = runCatching { URL(url) }.getOrNull() ?: return null
    val scheme = parsedUrl.protocol?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    val rawHost = parsedUrl.host.removeSurrounding("[", "]")
    val host = normalizeNotificationHost(rawHost) ?: return null
    val port = when {
        parsedUrl.port == -1 -> -1
        scheme == "https" && parsedUrl.port == 443 -> -1
        scheme == "http" && parsedUrl.port == 80 -> -1
        else -> parsedUrl.port
    }
    val formattedHost = if (host.contains(':')) "[$host]" else host
    return if (port == -1) "$scheme://$formattedHost" else "$scheme://$formattedHost:$port"
}

internal fun isNotificationSourceAllowed(sourceUrl: String, pwaUrl: String): Boolean {
    val sourceOrigin = notificationOrigin(sourceUrl) ?: return false
    val configuredOrigin = notificationOrigin(pwaUrl) ?: return false
    if (sourceOrigin != configuredOrigin) return false
    val source = runCatching { URL(sourceOrigin) }.getOrNull() ?: return false
    return source.protocol == "https" || isLoopbackHost(source.host)
}

internal fun notificationAllowedOriginRules(pwaUrl: String): Set<String> =
    notificationOrigin(pwaUrl)
        ?.takeIf { isNotificationSourceAllowed(it, pwaUrl) }
        ?.let(::setOf)
        .orEmpty()

internal fun sanitizeNotificationText(value: String, maxLength: Int): String =
    value.asSequence()
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .joinToString("")
        .trim()
        .take(maxLength)

private fun sanitizeNotificationTag(tag: String): String =
    sanitizeNotificationText(tag, 80)

private fun normalizeNotificationHost(host: String): String? {
    val normalized = host.trim().trimEnd('.')
    if (normalized.isEmpty()) return null
    if (normalized.contains(':')) return normalized.lowercase()
    return runCatching { IDN.toASCII(normalized).lowercase() }
        .getOrNull()
        ?.takeIf(String::isNotEmpty)
}

private fun isLoopbackHost(host: String): Boolean =
    host.removeSurrounding("[", "]").let { normalized ->
        normalized.equals("localhost", ignoreCase = true) ||
            normalized == "127.0.0.1" ||
            normalized == "::1"
    }

internal fun effectiveNotificationPermission(
    context: Context,
    storedPermission: PwaNotificationPermission,
    pwaId: Long? = null
): PwaNotificationPermission {
    if (storedPermission != PwaNotificationPermission.GRANTED) return storedPermission
    val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pwaId != null) {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(notificationChannelId(pwaId))
        channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    } else {
        true
    }
    return if (runtimeGranted && notificationsEnabled && channelEnabled) {
        PwaNotificationPermission.GRANTED
    } else {
        PwaNotificationPermission.DENIED
    }
}

@SuppressLint("MissingPermission")
private fun publishPwaNotification(
    context: Context,
    pwa: PwaEntity,
    notificationId: Int,
    title: String,
    body: String
): Boolean {
    if (
        effectiveNotificationPermission(
            context,
            PwaNotificationPermission.GRANTED,
            pwa.id
        ) != PwaNotificationPermission.GRANTED
    ) {
        return false
    }

    ensurePwaNotificationChannel(context, pwa)
    val openIntent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_PWA_NOTIFICATION
        data = Uri.parse("netnest://notification/${pwa.id}/$notificationId")
        putExtra(MainActivity.EXTRA_PWA_ID, pwa.id)
        putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId.toString())
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId xor pwa.id.hashCode(),
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, notificationChannelId(pwa.id))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(body.takeIf(String::isNotEmpty)?.let(NotificationCompat.BigTextStyle()::bigText))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setGroup(notificationTag(pwa.id))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context).notify(
        notificationTag(pwa.id),
        notificationId,
        notification
    )
    return true
}

private fun ensurePwaNotificationChannel(context: Context, pwa: PwaEntity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        notificationChannelId(pwa.id),
        pwa.name.take(40),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "来自 ${pwa.name} 的网页通知"
    }
    manager.createNotificationChannel(channel)
}

fun clearPwaNotificationData(context: Context, pwa: PwaEntity) {
    val store = PwaNotificationPermissionStore(context)
    val manager = NotificationManagerCompat.from(context)
    store.notificationIds(pwa.id).forEach { notificationId ->
        manager.cancel(notificationTag(pwa.id), notificationId)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(notificationChannelId(pwa.id))
    }
    store.clearAll(pwa.id)
}

fun resetPwaNotificationPermission(context: Context, pwa: PwaEntity) {
    PwaNotificationPermissionStore(context).clearPermission(pwa.id)
}

private fun notificationIdFor(
    pwaId: Long,
    tag: String,
    nextUntaggedId: () -> Int
): Int =
    if (tag.isNotEmpty()) {
        "$pwaId|$tag".hashCode() or Int.MIN_VALUE
    } else {
        nextUntaggedId()
    }

private fun notificationChannelId(pwaId: Long) = "netnest.pwa.$pwaId"
private fun notificationTag(pwaId: Long) = "netnest:pwa:$pwaId"

internal fun getPwaNotificationSupportJs(capabilityToken: String): String {
    val token = JSONObject.quote(capabilityToken)
    return """
        (function() {
            if (window.__netnestNotificationInstalled) return;
            if (window.top !== window) return;
            const bridge = window.NetNestNotification;
            if (!bridge) return;

            const token = $token;
            const pending = new Map();
            const notifications = new Map();
            let sequence = 0;

            function permission() {
                try {
                    return bridge.getPermission(token, String(location.href));
                } catch (_) {
                    return 'denied';
                }
            }

            function resolvePermission(requestId, value) {
                const resolve = pending.get(String(requestId));
                if (!resolve) return;
                pending.delete(String(requestId));
                resolve(value === 'granted' || value === 'denied' ? value : 'default');
            }

            Object.defineProperty(window, '__netnestNotificationResolve', {
                value: resolvePermission,
                configurable: false,
                enumerable: false,
                writable: false
            });

            Object.defineProperty(window, '__netnestNotificationClick', {
                value: function(notificationId) {
                    const id = String(notificationId);
                    const notification = notifications.get(id);
                    if (notification) {
                        notifications.delete(id);
                        notification.__netnestId = '';
                        notification.dispatchEvent(new Event('click'));
                    }
                    return true;
                },
                configurable: false,
                enumerable: false,
                writable: false
            });

            class NetNestNotification extends EventTarget {
                static get permission() {
                    return permission();
                }

                static requestPermission(callback) {
                    const current = permission();
                    const userActivation = navigator.userActivation;
                    const mayPrompt = !!userActivation && userActivation.isActive;
                    const result = current !== 'default' || !mayPrompt
                        ? Promise.resolve(current)
                        : new Promise((resolve) => {
                            const requestId = 'pwa_' + Date.now() + '_' + (++sequence);
                            pending.set(requestId, resolve);
                            try {
                                bridge.requestPermission(token, requestId, String(location.href));
                            } catch (_) {
                                pending.delete(requestId);
                                resolve('denied');
                            }
                        });
                    if (typeof callback === 'function') result.then(callback);
                    return result;
                }

                constructor(title, options) {
                    super();
                    const settings = options && typeof options === 'object' ? options : {};
                    if (permission() !== 'granted') {
                        throw new DOMException(
                            'Notification permission has not been granted',
                            'NotAllowedError'
                        );
                    }
                    const result = bridge.show(
                        token,
                        String(location.href),
                        String(title == null ? '' : title),
                        String(settings.body == null ? '' : settings.body),
                        String(settings.tag == null ? '' : settings.tag)
                    );
                    if (typeof result !== 'string' || result.indexOf('OK:') !== 0) {
                        const message = result === 'RATE_LIMITED'
                            ? 'Notification rate limit exceeded'
                            : 'Notification could not be displayed';
                        throw new DOMException(message, 'NotAllowedError');
                    }
                    this.__netnestId = result.substring(3);
                    notifications.set(this.__netnestId, this);
                    this.title = String(title == null ? '' : title);
                    this.body = String(settings.body == null ? '' : settings.body);
                    this.tag = String(settings.tag == null ? '' : settings.tag);
                    queueMicrotask(() => this.dispatchEvent(new Event('show')));
                }

                close() {
                    if (!this.__netnestId) return;
                    try {
                        bridge.cancel(token, this.__netnestId);
                    } catch (_) {}
                    notifications.delete(this.__netnestId);
                    this.__netnestId = '';
                    this.dispatchEvent(new Event('close'));
                }
            }

            ['onclick', 'onshow', 'onclose', 'onerror'].forEach((property) => {
                Object.defineProperty(NetNestNotification.prototype, property, {
                    get: function() { return this['__' + property] || null; },
                    set: function(handler) {
                        const previous = this['__' + property];
                        const eventName = property.substring(2);
                        if (typeof previous === 'function') {
                            this.removeEventListener(eventName, previous);
                        }
                        this['__' + property] = typeof handler === 'function' ? handler : null;
                        if (typeof handler === 'function') {
                            this.addEventListener(eventName, handler);
                        }
                    }
                });
            });

            try {
                Object.defineProperty(window, 'Notification', {
                    value: NetNestNotification,
                    configurable: true,
                    enumerable: false,
                    writable: false
                });
            } catch (_) {
                return;
            }
            try {
                if (typeof window.ServiceWorkerRegistration === 'function') {
                    Object.defineProperty(
                        window.ServiceWorkerRegistration.prototype,
                        'showNotification',
                        {
                            value: function(title, options) {
                                return Promise.resolve().then(function() {
                                    new NetNestNotification(title, options);
                                });
                            },
                            configurable: true,
                            enumerable: false,
                            writable: false
                        }
                    );
                }
            } catch (_) {}
            Object.defineProperty(window, '__netnestNotificationInstalled', {
                value: true,
                configurable: false,
                enumerable: false,
                writable: false
            });
        })();
    """.trimIndent()
}

internal fun buildPwaNotificationClickScript(notificationId: String): String {
    val quotedId = JSONObject.quote(notificationId)
    return """
        (function() {
            if (typeof window.__netnestNotificationClick !== 'function') return false;
            window.__netnestNotificationClick($quotedId);
            return true;
        })();
    """.trimIndent()
}

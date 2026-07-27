package com.pwa.shell.ui

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val STORAGE_PERSISTENCE_UNSUPPORTED_MESSAGE =
    "Android WebView 不支持浏览器持久化标记，但 NetNest 不会主动清理数据。"

internal class StoragePersistenceNoticeGate(capabilityToken: String) {
    private val capabilityTokenBytes = capabilityToken.toByteArray(Charsets.UTF_8)
    private val notified = AtomicBoolean(false)

    fun shouldNotify(candidate: String): Boolean =
        MessageDigest.isEqual(
            capabilityTokenBytes,
            candidate.toByteArray(Charsets.UTF_8)
        ) && notified.compareAndSet(false, true)
}

internal class StoragePersistenceNoticeBridge(
    capabilityToken: String,
    private val onUnsupported: () -> Unit
) {
    private val gate = StoragePersistenceNoticeGate(capabilityToken)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun notifyUnsupported(token: String) {
        if (gate.shouldNotify(token)) {
            mainHandler.post(onUnsupported)
        }
    }
}

internal fun getStoragePersistenceNoticeJs(capabilityToken: String): String {
    val encodedToken = Json.encodeToString(capabilityToken)
    return """
        (function() {
            if (window.top !== window ||
                window.__netnest_storage_persistence_notice_installed) return;
            window.__netnest_storage_persistence_notice_installed = true;
            const bridge = window.NetNestStoragePersistence;
            if (!bridge || !navigator.storage ||
                typeof navigator.storage.persist !== "function") return;
            try { delete window.NetNestStoragePersistence; } catch (_) {}
            const token = $encodedToken;
            const storage = navigator.storage;
            const originalPersist = storage.persist;
            const wrappedPersist = async function() {
                const persisted = await originalPersist.call(storage);
                if (!persisted) bridge.notifyUnsupported(token);
                return persisted;
            };
            try {
                Object.defineProperty(storage, "persist", {
                    configurable: true,
                    value: wrappedPersist
                });
            } catch (_) {
                try { storage.persist = wrappedPersist; } catch (_) {}
            }
        })();
    """.trimIndent()
}

package com.pwa.shell.ui

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class BrowserDownloadKind {
    NETWORK,
    WEB_DATA
}

internal data class BrowserDownloadRequest(
    val url: String,
    val suggestedFileName: String,
    val mimeType: String,
    val contentLength: Long,
    val userAgent: String,
    val contentDisposition: String?,
    val referer: String?,
    val kind: BrowserDownloadKind,
    val isJavascriptHandle: Boolean = false,
    val cookieHeader: String? = null
)

internal fun classifyDownloadKind(url: String): BrowserDownloadKind? {
    val separator = url.indexOf(':')
    if (separator <= 0) return null
    return when (url.substring(0, separator).lowercase()) {
        "http", "https" -> BrowserDownloadKind.NETWORK
        "blob", "data" -> BrowserDownloadKind.WEB_DATA
        else -> null
    }
}

internal fun createBrowserDownloadRequest(
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    contentLength: Long,
    referer: String?,
    cookieHeader: String? = null
): BrowserDownloadRequest? {
    val kind = classifyDownloadKind(url) ?: return null
    val normalizedMime = normalizeMimeType(mimeType)
    val guessedName = URLUtil.guessFileName(url, contentDisposition, normalizedMime)
    return BrowserDownloadRequest(
        url = url,
        suggestedFileName = sanitizeDownloadFileName(guessedName),
        mimeType = normalizedMime,
        contentLength = contentLength,
        userAgent = sanitizeHeaderValue(userAgent.orEmpty()),
        contentDisposition = contentDisposition,
        referer = sanitizeHeaderValue(referer.orEmpty()).takeIf { it.isNotEmpty() },
        kind = kind,
        cookieHeader = sanitizeHeaderValue(cookieHeader.orEmpty()).takeIf { it.isNotEmpty() }
    )
}

internal fun sanitizeDownloadFileName(rawName: String): String {
    val cleaned = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
        .trim()
        .trim('.')
        .ifEmpty { "download" }
    if (cleaned.length <= 180) return cleaned

    val dot = cleaned.lastIndexOf('.')
    if (dot <= 0 || cleaned.length - dot > 20) return cleaned.take(180)
    val extension = cleaned.substring(dot)
    return cleaned.take(180 - extension.length) + extension
}

internal fun formatDownloadSize(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 10 || value % 1.0 == 0.0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

internal fun enqueueNetworkDownload(context: Context, request: BrowserDownloadRequest): Long {
    require(request.kind == BrowserDownloadKind.NETWORK)
    val destinationName = uniquePublicDownloadName(request.suggestedFileName)
    val downloadRequest = DownloadManager.Request(Uri.parse(request.url))
        .setTitle(destinationName)
        .setDescription("正在下载到 Download/NetNest")
        .setMimeType(request.mimeType)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "NetNest/$destinationName"
        )

    if (request.userAgent.isNotEmpty()) {
        downloadRequest.addRequestHeader("User-Agent", request.userAgent)
    }
    request.referer?.let { downloadRequest.addRequestHeader("Referer", it) }
    request.cookieHeader?.let { downloadRequest.addRequestHeader("Cookie", it) }

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(downloadRequest)
}

internal class WebDataDownloadBridge(
    private val context: Context,
    capabilityToken: String,
    private val onRequested: (BrowserDownloadRequest) -> Unit,
    private val onCompleted: (String) -> Unit,
    private val onFailed: (String) -> Unit
) {
    private data class DownloadSession(
        val displayName: String,
        val output: OutputStream,
        val uri: Uri?,
        val file: File?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val capabilityTokenBytes = capabilityToken.toByteArray(Charsets.UTF_8)
    private val grants = ConcurrentHashMap.newKeySet<String>()
    private val sessions = ConcurrentHashMap<String, DownloadSession>()

    @JavascriptInterface
    fun requestDownload(
        token: String,
        requestId: String,
        suggestedFileName: String,
        mimeType: String
    ) {
        if (!isAuthorized(token)) return
        if (!requestId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return
        val request = BrowserDownloadRequest(
            url = requestId,
            suggestedFileName = sanitizeDownloadFileName(
                suggestedFileName.ifBlank { "download" }
            ),
            mimeType = normalizeMimeType(mimeType),
            contentLength = -1,
            userAgent = "",
            contentDisposition = null,
            referer = null,
            kind = BrowserDownloadKind.WEB_DATA,
            isJavascriptHandle = true
        )
        mainHandler.post { onRequested(request) }
    }

    fun authorize(grantId: String) {
        grants.add(grantId)
    }

    fun revoke(grantId: String) {
        grants.remove(grantId)
    }

    @JavascriptInterface
    fun open(
        token: String,
        grantId: String,
        suggestedFileName: String,
        mimeType: String
    ): String {
        if (!isAuthorized(token) || !grants.remove(grantId)) return ""
        return runCatching {
            val sessionId = UUID.randomUUID().toString()
            sessions[sessionId] = createSession(
                sanitizeDownloadFileName(suggestedFileName),
                normalizeMimeType(mimeType)
            )
            sessionId
        }.getOrElse {
            notifyFailure("无法创建下载文件：${it.localizedMessage ?: "未知错误"}")
            ""
        }
    }

    @JavascriptInterface
    fun writeChunk(token: String, sessionId: String, base64Chunk: String): Boolean {
        if (!isAuthorized(token)) return false
        val session = sessions[sessionId] ?: return false
        return runCatching {
            val bytes = Base64.decode(base64Chunk, Base64.NO_WRAP)
            synchronized(session) {
                session.output.write(bytes)
            }
            true
        }.getOrElse {
            abortSession(sessionId)
            notifyFailure("写入下载文件失败：${it.localizedMessage ?: "未知错误"}")
            false
        }
    }

    @JavascriptInterface
    fun close(token: String, sessionId: String): Boolean {
        if (!isAuthorized(token)) return false
        val session = sessions.remove(sessionId) ?: return false
        return runCatching {
            synchronized(session) {
                session.output.flush()
                session.output.close()
            }
            publishSession(session)
            mainHandler.post { onCompleted(session.displayName) }
            true
        }.getOrElse {
            deleteSessionTarget(session)
            notifyFailure("完成下载失败：${it.localizedMessage ?: "未知错误"}")
            false
        }
    }

    @JavascriptInterface
    fun fail(token: String, message: String) {
        if (!isAuthorized(token)) return
        notifyFailure(message.take(300))
    }

    @JavascriptInterface
    fun abort(token: String, sessionId: String) {
        if (!isAuthorized(token)) return
        abortSession(sessionId)
    }

    fun cancelAll() {
        grants.clear()
        sessions.keys.toList().forEach(::abortSession)
    }

    private fun createSession(fileName: String, mimeType: String): DownloadSession {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/NetNest"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("系统下载目录不可用")
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: run {
                    context.contentResolver.delete(uri, null, null)
                    error("无法打开下载文件")
                }
            DownloadSession(fileName, output, uri, null)
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NetNest"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                error("无法创建 Download/NetNest 目录")
            }
            val file = uniqueFile(directory, fileName)
            DownloadSession(file.name, FileOutputStream(file), null, file)
        }
    }

    private fun publishSession(session: DownloadSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = session.uri ?: return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } else {
            val file = session.file ?: return
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null
            )
        }
    }

    private fun abortSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        runCatching { session.output.close() }
        deleteSessionTarget(session)
    }

    private fun deleteSessionTarget(session: DownloadSession) {
        session.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        session.file?.let { runCatching { it.delete() } }
    }

    private fun notifyFailure(message: String) {
        mainHandler.post { onFailed(message) }
    }

    private fun isAuthorized(candidate: String): Boolean {
        return MessageDigest.isEqual(
            capabilityTokenBytes,
            candidate.toByteArray(Charsets.UTF_8)
        )
    }
}

internal fun getWebDataDownloadSupportJs(capabilityToken: String): String {
    val encodedToken = org.json.JSONObject.quote(capabilityToken)
    return """
        (function() {
            if (window.__netnest_download_support_installed) return;
            window.__netnest_download_support_installed = true;
            const bridge = window.NetNestDownload;
            if (!bridge) return;
            try { delete window.NetNestDownload; } catch (_) {}
            const token = $encodedToken;
            const pendingDownloads = new Map();

            function readBlobChunk(blob) {
                if (typeof blob.arrayBuffer === "function") {
                    return blob.arrayBuffer();
                }
                return new Promise(function(resolve, reject) {
                    const reader = new FileReader();
                    reader.onload = function() { resolve(reader.result); };
                    reader.onerror = function() {
                        reject(reader.error || new Error("读取下载数据失败"));
                    };
                    reader.readAsArrayBuffer(blob);
                });
            }

            async function startDownload(
                grantId,
                urlOrHandle,
                isJavascriptHandle,
                suggestedFileName,
                mimeType
            ) {
                let sessionId = "";
                let downloadUrl = "";
                try {
                    const pending = isJavascriptHandle
                        ? pendingDownloads.get(urlOrHandle)
                        : null;
                    if (isJavascriptHandle && !pending) {
                        throw new Error("网页下载请求已失效");
                    }
                    downloadUrl = pending ? pending.url : urlOrHandle;
                    if (isJavascriptHandle) pendingDownloads.delete(urlOrHandle);
                    const response = await fetch(downloadUrl);
                    if (!response.ok) throw new Error("无法读取网页生成的下载内容");
                    const blob = await response.blob();
                    sessionId = bridge.open(
                        token,
                        grantId,
                        suggestedFileName || "download",
                        blob.type || mimeType || "application/octet-stream"
                    );
                    if (!sessionId) throw new Error("无法创建系统下载文件");

                    const chunkSize = 128 * 1024;
                    for (let offset = 0; offset < blob.size; offset += chunkSize) {
                        const buffer = await readBlobChunk(
                            blob.slice(offset, offset + chunkSize)
                        );
                        const bytes = new Uint8Array(buffer);
                        let binary = "";
                        for (let index = 0; index < bytes.length; index += 0x8000) {
                            binary += String.fromCharCode.apply(
                                null,
                                bytes.subarray(index, Math.min(index + 0x8000, bytes.length))
                            );
                        }
                        if (!bridge.writeChunk(token, sessionId, btoa(binary))) {
                            throw new Error("写入系统下载文件失败");
                        }
                    }
                    if (!bridge.close(token, sessionId)) {
                        throw new Error("无法完成系统下载文件");
                    }
                    sessionId = "";
                } catch (error) {
                    if (sessionId) bridge.abort(token, sessionId);
                    bridge.fail(
                        token,
                        error && error.message ? error.message : "网页下载失败"
                    );
                } finally {
                    if (downloadUrl.indexOf("blob:") === 0) {
                        try { URL.revokeObjectURL(downloadUrl); } catch (_) {}
                    }
                }
            }

            Object.defineProperty(window, "__netnestStartDownload", {
                value: startDownload,
                writable: false,
                configurable: false
            });
            Object.defineProperty(window, "__netnestCancelDownload", {
                value: function(requestId) {
                    pendingDownloads.delete(requestId);
                },
                writable: false,
                configurable: false
            });

            document.addEventListener("click", function(event) {
                const target = event.target;
                const anchor = target && target.closest ? target.closest("a[download]") : null;
                if (!anchor) return;
                const url = anchor.href || "";
                if (url.indexOf("blob:") !== 0 && url.indexOf("data:") !== 0) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                let mimeType = "";
                if (url.indexOf("data:") === 0) {
                    const comma = url.indexOf(",");
                    if (comma > 5) mimeType = url.substring(5, comma).split(";")[0];
                }
                const requestId = typeof crypto !== "undefined" &&
                    typeof crypto.randomUUID === "function"
                    ? crypto.randomUUID()
                    : Date.now().toString(36) + Math.random().toString(36).substring(2);
                pendingDownloads.set(requestId, { url: url });
                bridge.requestDownload(
                    token,
                    requestId,
                    anchor.getAttribute("download") || "download",
                    mimeType
                );
            }, true);
        })();
    """.trimIndent()
}

internal fun buildWebDataDownloadStartScript(
    grantId: String,
    request: BrowserDownloadRequest
): String {
    val encodedGrant = org.json.JSONObject.quote(grantId)
    val encodedUrl = org.json.JSONObject.quote(request.url)
    val isJavascriptHandle = request.isJavascriptHandle
    val encodedFileName = org.json.JSONObject.quote(request.suggestedFileName)
    val encodedMime = org.json.JSONObject.quote(request.mimeType)
    return """
        (function() {
            if (typeof window.__netnestStartDownload !== "function") return false;
            window.__netnestStartDownload(
                $encodedGrant,
                $encodedUrl,
                $isJavascriptHandle,
                $encodedFileName,
                $encodedMime
            );
            return true;
        })();
    """.trimIndent()
}

internal fun buildWebDataDownloadCancelScript(request: BrowserDownloadRequest): String {
    if (!request.isJavascriptHandle) return ""
    val encodedHandle = org.json.JSONObject.quote(request.url)
    return """
        if (typeof window.__netnestCancelDownload === "function") {
            window.__netnestCancelDownload($encodedHandle);
        }
    """.trimIndent()
}

private fun normalizeMimeType(mimeType: String?): String {
    return mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
        ?: "application/octet-stream"
}

internal fun sanitizeHeaderValue(value: String): String {
    return value.replace("\r", "").replace("\n", "").trim()
}

private fun uniquePublicDownloadName(fileName: String): String {
    @Suppress("DEPRECATION")
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "NetNest"
    )
    return uniqueFile(directory, fileName).name
}

private fun uniqueFile(directory: File, fileName: String): File {
    val initial = File(directory, fileName)
    if (!initial.exists()) return initial

    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val extension = if (dot > 0) fileName.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = File(directory, "$base ($index)$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

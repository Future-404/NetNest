package com.pwa.shell.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class IconDownloader(private val okHttpClient: OkHttpClient) {

    suspend fun downloadIcon(context: Context, iconUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(iconUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null

                // Determine extension based on Content-Type or URL path
                val contentType = response.header("Content-Type")
                val extension = getExtension(iconUrl, contentType)

                // Generate a unique filename using MD5 hash of the URL
                val filename = "${md5(iconUrl)}.$extension"
                val destFile = File(context.filesDir, "pwa_icons/$filename")
                
                // Ensure parent directories exist
                destFile.parentFile?.mkdirs()

                // Write bytes to file
                body.byteStream().use { inputStream ->
                    FileOutputStream(destFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getExtension(url: String, contentType: String?): String {
        contentType?.let {
            if (it.contains("image/svg+xml")) return "svg"
            if (it.contains("image/png")) return "png"
            if (it.contains("image/jpeg") || it.contains("image/jpg")) return "jpg"
            if (it.contains("image/webp")) return "webp"
            if (it.contains("image/x-icon") || it.contains("image/vnd.microsoft.icon")) return "ico"
        }

        // Fallback to URL path extraction
        val lastPathSegment = url.substringAfterLast('/')
        val ext = lastPathSegment.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 4) {
            return ext
        }

        return "png" // Default fallback
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

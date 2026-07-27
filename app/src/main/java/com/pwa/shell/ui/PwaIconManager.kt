package com.pwa.shell.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PwaIconManager {
    private const val MAX_IMPORT_BYTES = 10L * 1024 * 1024
    private const val STORED_ICON_SIZE = 512
    private const val SHORTCUT_ICON_SIZE = 256
    private const val ICON_DIRECTORY = "pwa_icons"

    suspend fun importCustomIcon(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val temporary = File.createTempFile("pwa_icon_", ".tmp", context.cacheDir)
                try {
                    copyUriWithLimit(context, uri, temporary)
                    val bitmap = decodeSquareBitmap(context, temporary, STORED_ICON_SIZE)
                        ?: throw IOException("无法识别该图片，请选择 PNG、JPG、WebP 或 SVG 文件")
                    try {
                        saveBitmap(context, bitmap, "custom_${UUID.randomUUID()}.png")
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    temporary.delete()
                }
            }
        }

    suspend fun shortcutBitmap(context: Context, iconPath: String, name: String): Bitmap =
        withContext(Dispatchers.IO) {
            val iconFile = iconPath.takeIf { it.isNotBlank() }?.let(::File)
            if (iconFile?.isFile == true) {
                decodeSquareBitmap(context, iconFile, SHORTCUT_ICON_SIZE)
                    ?: createPlaceholderBitmap(name, SHORTCUT_ICON_SIZE)
            } else {
                createPlaceholderBitmap(name, SHORTCUT_ICON_SIZE)
            }
        }

    fun deleteManagedIcon(context: Context, iconPath: String) {
        if (iconPath.isBlank()) return
        runCatching {
            val directory = iconDirectory(context)
            val target = File(iconPath)
            if (isDirectChildPath(directory, target)) target.delete()
        }
    }

    private fun copyUriWithLimit(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选图片")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > MAX_IMPORT_BYTES) {
                        throw IOException("图片不能超过 10 MB")
                    }
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("所选图片为空")
            }
        }
    }

    private suspend fun decodeSquareBitmap(
        context: Context,
        file: File,
        size: Int
    ): Bitmap? {
        val imageLoader = ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(size)
            .allowHardware(false)
            .build()
        val drawable = (imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = min(size.toFloat() / sourceWidth, size.toFloat() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val left = (size - width) / 2
        val top = (size - height) / 2
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(left, top, left + width, top + height)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): String {
        val outputFile = File(iconDirectory(context), fileName)
        try {
            FileOutputStream(outputFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("无法保存处理后的图标")
                }
            }
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        }
        return outputFile.absolutePath
    }

    private fun createPlaceholderBitmap(name: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(232, 230, 226))
        val label = name.trim().take(1).uppercase().ifEmpty { "P" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(73, 69, 79)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.42f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, size / 2f, baseline, paint)
        return bitmap
    }

    private fun iconDirectory(context: Context): File =
        File(context.filesDir, ICON_DIRECTORY).apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建图标目录")
        }
}

internal fun isDirectChildPath(directory: File, target: File): Boolean =
    runCatching {
        target.canonicalFile.parentFile == directory.canonicalFile
    }.getOrDefault(false)

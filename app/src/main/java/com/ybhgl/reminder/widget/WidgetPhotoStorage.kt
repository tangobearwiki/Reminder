package com.ybhgl.reminder.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 组件背景图片缓存存储
 * 将用户从相册/文件选择器选择的图片复制到应用私有目录，
 * 以便 RemoteViews 可以稳定读取并在重启后继续使用。
 */
class WidgetPhotoStorage(private val context: Context) {

    private val photoDir: File
        get() = File(context.filesDir, "widget-photos").apply { mkdirs() }

    /**
     * 复制新图片到缓存目录，并删除旧图片（若不再使用）。
     * 返回新的图片绝对路径列表。
     */
    suspend fun replacePhotos(uris: List<Uri>, existingPaths: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val cachedFiles = uris.take(20).mapNotNull { uri -> cacheUri(uri) }

            existingPaths
                .map(::File)
                .filter { old -> cachedFiles.none { it.absolutePath == old.absolutePath } }
                .forEach { it.delete() }

            cachedFiles.map { it.absolutePath }
        }

    /** 清空指定图片缓存 */
    suspend fun clearPhotos(existingPaths: List<String>) = withContext(Dispatchers.IO) {
        existingPaths.map(::File).forEach { it.delete() }
    }

    /** 加载缓存图片为 Bitmap（带采样压缩，避免大图撑爆 RemoteViews） */
    fun loadBitmap(path: String, requestedWidth: Int = 1600, requestedHeight: Int = 1600): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun cacheUri(uri: Uri): File? {
        val extension = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(photoDir, "${UUID.randomUUID()}.$extension")

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            file
        }.getOrNull()
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > requestedHeight || width > requestedWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= requestedHeight &&
                (halfWidth / inSampleSize) >= requestedWidth
            ) {
                inSampleSize *= 2
            }
        }
        return maxOf(1, inSampleSize)
    }
}
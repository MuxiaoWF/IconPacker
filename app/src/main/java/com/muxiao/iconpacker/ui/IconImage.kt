package com.muxiao.iconpacker.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory

/**
 * 从私有目录文件路径加载图标（MVP 不引第三方图片库；
 * 图标尺寸已知且有限，直接采样解码即可）。
 */
@Composable
fun rememberIconBitmap(path: String, maxPx: Int = 192): ImageBitmap? {
    return remember(path) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0) return@remember null
        opts.inSampleSize = sampleSizeFor(opts.outWidth, opts.outHeight, maxPx)
        opts.inJustDecodeBounds = false
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }
}

@Composable
fun IconImage(path: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = rememberIconBitmap(path)
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier)
    }
}

internal fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
    var sample = 1
    var minSide = minOf(width, height)
    while (minSide / 2 >= maxPx) {
        minSide /= 2
        sample *= 2
    }
    return sample
}

/** Drawable（如应用图标）→ ImageBitmap，尺寸夹取防 0 尺寸异常 */
internal fun android.graphics.drawable.Drawable.toImageBitmap(): ImageBitmap {
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

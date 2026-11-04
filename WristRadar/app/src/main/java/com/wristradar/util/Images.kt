package com.wristradar.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object Images {
    /** Max edge for images sent between watches (keeps BLE transfers to tens of KB). */
    const val SEND_MAX_EDGE = 480
    const val SEND_QUALITY = 72
    const val MAX_RECEIVE_BYTES = 400_000

    fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val edge = min(src.width, src.height)
        val x = (src.width - edge) / 2
        val y = (src.height - edge) / 2
        val cropped = Bitmap.createBitmap(src, x, y, edge, edge)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    fun fitWithin(src: Bitmap, maxEdge: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxEdge) return src
        val s = maxEdge.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Decodes a content Uri with downsampling (avoids loading 12MP photos fully into RAM). */
    fun decodeUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val m = max(w, h)
            if (m > maxEdge) {
                val s = maxEdge.toFloat() / m
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    fun prepareForSending(src: Bitmap): Triple<ByteArray, Int, Int> {
        val scaled = fitWithin(src, SEND_MAX_EDGE)
        return Triple(jpeg(scaled, SEND_QUALITY), scaled.width, scaled.height)
    }

    /** Validates that received bytes really decode as an image of sane size. */
    fun validateJpeg(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 4096 || o.outHeight > 4096) return null
        return o.outWidth to o.outHeight
    }

    /** Generated default avatar: gradient disc with initials. */
    fun generatedAvatar(name: String, c1: Int, c2: Int, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = size * 0.42f
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        val initials = name.split(" ", "-", "_").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val y = size / 2f - (p.descent() + p.ascent()) / 2
        cv.drawText(initials, size / 2f, y, p)
        return bmp
    }
}

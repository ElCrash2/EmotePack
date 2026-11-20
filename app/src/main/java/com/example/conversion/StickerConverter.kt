package com.example.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

sealed class ConversionOutcome {
    data class Success(
        val webpBytes: ByteArray,
        val inspection: WebPInspectionResult,
        val isAnimated: Boolean
    ) : ConversionOutcome()

    data class NeedsTrimConfirmation(val totalDurationMs: Int) : ConversionOutcome()
    data class Failure(val reason: String) : ConversionOutcome()
}

object StickerConverter {

    const val STICKER_SIZE_PX = 512
    const val TRAY_SIZE_PX = 96
    const val MAX_STATIC_BYTES = 100 * 1024 // 100 KB
    const val MAX_ANIMATED_BYTES = 500 * 1024 // 500 KB
    const val MAX_TRAY_BYTES = 50 * 1024 // 50 KB
    const val MAX_ANIMATION_DURATION_MS = 10_000
    const val MIN_FRAME_DURATION_MS = 8

    fun convert(
        inputBytes: ByteArray,
        expectAnimated: Boolean,
        allowTrimOver10s: Boolean
    ): ConversionOutcome {
        if (inputBytes.isEmpty()) return ConversionOutcome.Failure("Empty input image")

        val decodedFrames = decodeInputFrames(inputBytes)
        if (decodedFrames.isEmpty()) {
            return ConversionOutcome.Failure("Unsupported or corrupt image format")
        }

        return try {
            if (decodedFrames.size > 1) {
                convertAnimated(decodedFrames, allowTrimOver10s)
            } else if (expectAnimated) {
                // If 7TV flagged emote as animated, check if source truly had only 1 frame
                // Never silently downgrade multi-frame animations; if single-frame source, convert as static
                convertStatic(decodedFrames.first().bitmap)
            } else {
                convertStatic(decodedFrames.first().bitmap)
            }
        } finally {
            decodedFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    private fun decodeInputFrames(bytes: ByteArray): List<DecodedFrame> {
        if (GifFrameDecoder.isGif(bytes)) {
            val frames = GifFrameDecoder.decode(bytes)
            if (frames.isNotEmpty()) return frames
        }

        if (WebPContainer.isWebP(bytes)) {
            val frames = WebPContainer.decodeAnimatedFrames(bytes)
            if (frames.isNotEmpty()) return frames
        }

        // Static PNG / JPEG / AVIF (API 31+ ImageDecoder handles AVIF too)
        val bitmap = decodeSingleBitmap(bytes) ?: return emptyList()
        return listOf(DecodedFrame(bitmap, 100))
    }

    private fun decodeSingleBitmap(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (_: Exception) {
                // Fallback to BitmapFactory
            }
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun convertStatic(source: Bitmap): ConversionOutcome {
        val canvasBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, STICKER_SIZE_PX)
        try {
            // 1. Try lossless first on API 30+ for pixel-perfect fidelity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val lossless = WebPContainer.encodeStaticWebP(canvasBitmap, 100, lossless = true)
                if (lossless.size <= MAX_STATIC_BYTES) {
                    val check = validateStatic(lossless)
                    if (check != null) return ConversionOutcome.Success(lossless, check, isAnimated = false)
                }
            }

            // 2. Progressive lossy quality compression (only reducing quality as much as needed)
            val qualities = intArrayOf(98, 92, 85, 76, 65, 52, 40, 28, 16)
            for (q in qualities) {
                val encoded = WebPContainer.encodeStaticWebP(canvasBitmap, q, lossless = false)
                if (encoded.size <= MAX_STATIC_BYTES) {
                    val check = validateStatic(encoded)
                    if (check != null) return ConversionOutcome.Success(encoded, check, isAnimated = false)
                }
            }

            // 3. Last resort: inner scale to 384 inside 512x512 transparent canvas
            val compactBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, 384)
            try {
                for (q in intArrayOf(65, 45, 30, 15)) {
                    val encoded = WebPContainer.encodeStaticWebP(compactBitmap, q, lossless = false)
                    if (encoded.size <= MAX_STATIC_BYTES) {
                        val check = validateStatic(encoded)
                        if (check != null) return ConversionOutcome.Success(encoded, check, isAnimated = false)
                    }
                }
            } finally {
                compactBitmap.recycle()
            }

            return ConversionOutcome.Failure("Could not compress static sticker under 100 KB")
        } finally {
            canvasBitmap.recycle()
        }
    }

    private fun convertAnimated(
        rawFrames: List<DecodedFrame>,
        allowTrimOver10s: Boolean
    ): ConversionOutcome {
        // Normalize frame timings (>= 8 ms per frame)
        var frames = rawFrames.map { frame ->
            val safeDur = if (frame.durationMs < MIN_FRAME_DURATION_MS) MIN_FRAME_DURATION_MS else frame.durationMs
            frame.copy(durationMs = safeDur)
        }

        val totalDuration = frames.sumOf { it.durationMs }
        if (totalDuration > MAX_ANIMATION_DURATION_MS) {
            if (!allowTrimOver10s) {
                return ConversionOutcome.NeedsTrimConfirmation(totalDuration)
            }
            frames = trimFramesToMaxDuration(frames, MAX_ANIMATION_DURATION_MS)
        }

        val scaledFrames = frames.map {
            DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, STICKER_SIZE_PX), it.durationMs)
        }

        try {
            // Pass A: Full frames, progressive quality
            val fullFrameQualities = intArrayOf(90, 78, 66, 54, 42, 30)
            for (q in fullFrameQualities) {
                val webp = WebPContainer.encodeAnimatedWebP(scaledFrames, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                if (webp.size <= MAX_ANIMATED_BYTES) {
                    val check = validateAnimated(webp)
                    if (check != null) return ConversionOutcome.Success(webp, check, isAnimated = true)
                }
            }

            // Pass B: Preserve timing via smart frame decimation (keep every 2nd frame, merge durations)
            if (scaledFrames.size > 4) {
                val halved = decimateFrames(scaledFrames, step = 2)
                for (q in intArrayOf(68, 54, 40, 28, 18)) {
                    val webp = WebPContainer.encodeAnimatedWebP(halved, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                    if (webp.size <= MAX_ANIMATED_BYTES) {
                        val check = validateAnimated(webp)
                        if (check != null) return ConversionOutcome.Success(webp, check, isAnimated = true)
                    }
                }
            }

            // Pass C: Step-3 frame decimation
            if (scaledFrames.size > 6) {
                val third = decimateFrames(scaledFrames, step = 3)
                for (q in intArrayOf(56, 40, 26, 15)) {
                    val webp = WebPContainer.encodeAnimatedWebP(third, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                    if (webp.size <= MAX_ANIMATED_BYTES) {
                        val check = validateAnimated(webp)
                        if (check != null) return ConversionOutcome.Success(webp, check, isAnimated = true)
                    }
                }
            }

            // Pass D: Slightly reduce inner content inside 512x512 transparent canvas + step-2 decimation
            val innerScaled = frames.map {
                DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, 360), it.durationMs)
            }
            try {
                val reduced = if (innerScaled.size > 4) decimateFrames(innerScaled, step = 2) else innerScaled
                for (q in intArrayOf(50, 35, 22, 12)) {
                    val webp = WebPContainer.encodeAnimatedWebP(reduced, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                    if (webp.size <= MAX_ANIMATED_BYTES) {
                        val check = validateAnimated(webp)
                        if (check != null) return ConversionOutcome.Success(webp, check, isAnimated = true)
                    }
                }
            } finally {
                innerScaled.forEach { it.bitmap.recycle() }
            }

            return ConversionOutcome.Failure("Could not compress animated sticker under 500 KB")
        } finally {
            scaledFrames.forEach { it.bitmap.recycle() }
        }
    }

    /**
     * Generates a 96x96 PNG tray icon under 50 KB from a sticker WebP file.
     */
    fun generateTrayIcon(stickerWebpFile: File): ByteArray? {
        if (!stickerWebpFile.exists()) return null
        val bytes = stickerWebpFile.readBytes()
        val frames = WebPContainer.decodeAnimatedFrames(bytes)
        val firstBitmap = frames.firstOrNull()?.bitmap ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null

        val trayBitmap = fitToSquareCanvas(firstBitmap, TRAY_SIZE_PX, TRAY_SIZE_PX)
        try {
            val out = ByteArrayOutputStream()
            trayBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val pngBytes = out.toByteArray()
            if (pngBytes.size <= MAX_TRAY_BYTES) return pngBytes

            // If unusually large PNG (>50KB at 96x96), fit inner content slightly smaller
            val smallerTray = fitToSquareCanvas(firstBitmap, TRAY_SIZE_PX, 84)
            try {
                val out2 = ByteArrayOutputStream()
                smallerTray.compress(Bitmap.CompressFormat.PNG, 100, out2)
                return out2.toByteArray().takeIf { it.size <= MAX_TRAY_BYTES } ?: pngBytes
            } finally {
                smallerTray.recycle()
            }
        } finally {
            trayBitmap.recycle()
            frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    fun validateExistingWebp(file: File, expectAnimated: Boolean): WebPInspectionResult? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        return if (expectAnimated) validateAnimated(bytes) else validateStatic(bytes)
    }

    private fun validateStatic(bytes: ByteArray): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_STATIC_BYTES) return null
        return info
    }

    private fun validateAnimated(bytes: ByteArray): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (!info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_ANIMATED_BYTES) return null
        if (info.frameCount < 2) return null
        if (info.minFrameDurationMs < MIN_FRAME_DURATION_MS) return null
        if (info.totalDurationMs > MAX_ANIMATION_DURATION_MS) return null
        return info
    }

    private fun trimFramesToMaxDuration(frames: List<DecodedFrame>, maxMs: Int): List<DecodedFrame> {
        val kept = mutableListOf<DecodedFrame>()
        var elapsed = 0
        for (f in frames) {
            if (elapsed >= maxMs) break
            val remaining = maxMs - elapsed
            if (f.durationMs <= remaining) {
                kept.add(f)
                elapsed += f.durationMs
            } else if (remaining >= MIN_FRAME_DURATION_MS) {
                kept.add(f.copy(durationMs = remaining))
                elapsed += remaining
            } else {
                break
            }
        }
        return if (kept.size >= 2) kept else frames.take(2)
    }

    private fun decimateFrames(frames: List<DecodedFrame>, step: Int): List<DecodedFrame> {
        val out = mutableListOf<DecodedFrame>()
        var i = 0
        while (i < frames.size) {
            var accumulatedMs = 0
            for (j in i until minOf(i + step, frames.size)) {
                accumulatedMs += frames[j].durationMs
            }
            out.add(frames[i].copy(durationMs = maxOf(MIN_FRAME_DURATION_MS, accumulatedMs)))
            i += step
        }
        return if (out.size >= 2) out else frames.take(2)
    }

    /**
     * Scales source bitmap proportionally (no distortion, no cropping) to fit inside
     * [innerMaxPx] centered on a transparent [canvasSizePx] x [canvasSizePx] canvas.
     * No white outline or artificial background is added.
     */
    private fun fitToSquareCanvas(source: Bitmap, canvasSizePx: Int, innerMaxPx: Int): Bitmap {
        val result = Bitmap.createBitmap(canvasSizePx, canvasSizePx, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(result)

        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val scale = minOf(innerMaxPx.toFloat() / srcW, innerMaxPx.toFloat() / srcH)
        val destW = srcW * scale
        val destH = srcH * scale
        val left = (canvasSizePx - destW) / 2f
        val top = (canvasSizePx - destH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + destW, top + destH), paint)
        return result
    }
}

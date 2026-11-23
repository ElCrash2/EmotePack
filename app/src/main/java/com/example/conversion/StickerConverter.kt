package com.example.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

enum class StickerConversionMode {
    STATIC,
    ANIMATED,
    FAKE_VID
}

/**
 * Conversion parameters inspired by laggykiller/sticker-convert's WhatsApp preset:
 * quality/fps/res min-max bounds, power-curve interpolation, byte limits, and binary search steps.
 */
data class ConverterParams(
    val qualityMin: Int = 15,
    val qualityMax: Int = 98,
    val qualityPower: Float = 1.0f,
    val fpsMin: Int = 5,
    val fpsMax: Int = 30,
    val fpsPower: Float = 1.0f,
    val resMin: Int = 256,
    val resMax: Int = 512,
    val resPower: Float = 1.0f,
    val imgSizeMax: Int = 100_000,
    val vidSizeMax: Int = 500_000,
    val traySizeMax: Int = 50_000,
    val durationMaxMs: Int = 10_000,
    val minFrameDurationMs: Int = 8,
    val steps: Int = 6
)

sealed class ConversionOutcome {
    data class Success(
        val webpBytes: ByteArray,
        val inspection: WebPInspectionResult,
        val isAnimated: Boolean,
        val conversionMode: StickerConversionMode
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

    private val defaultParams = ConverterParams()

    fun convert(
        inputBytes: ByteArray,
        expectAnimated: Boolean,
        allowTrimOver10s: Boolean
    ): ConversionOutcome {
        val mode = if (expectAnimated) StickerConversionMode.ANIMATED else StickerConversionMode.STATIC
        return convert(inputBytes, mode, allowTrimOver10s)
    }

    fun convert(
        inputBytes: ByteArray,
        mode: StickerConversionMode,
        allowTrimOver10s: Boolean,
        params: ConverterParams = defaultParams
    ): ConversionOutcome {
        if (inputBytes.isEmpty()) return ConversionOutcome.Failure("Empty input image")

        // Fast pass-through if original input is already a fully WhatsApp-compliant WebP in the target mode
        when (mode) {
            StickerConversionMode.STATIC -> {
                validateStatic(inputBytes)?.let { info ->
                    return ConversionOutcome.Success(inputBytes, info, isAnimated = false, mode)
                }
            }
            StickerConversionMode.ANIMATED, StickerConversionMode.FAKE_VID -> {
                validateAnimated(inputBytes)?.let { info ->
                    return ConversionOutcome.Success(inputBytes, info, isAnimated = true, StickerConversionMode.ANIMATED)
                }
            }
        }

        val decodedFrames = decodeInputFrames(inputBytes)
        if (decodedFrames.isEmpty()) {
            return ConversionOutcome.Failure("Unsupported or corrupt image format")
        }

        return try {
            when (mode) {
                StickerConversionMode.STATIC -> {
                    convertStatic(decodedFrames.first().bitmap, params)
                }
                StickerConversionMode.FAKE_VID -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, allowTrimOver10s, params)
                    } else {
                        convertFakeVid(decodedFrames.first().bitmap, params)
                    }
                }
                StickerConversionMode.ANIMATED -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, allowTrimOver10s, params)
                    } else {
                        // Single-frame source inside an animated pack -> encode as motionless fake-vid animated WebP
                        convertFakeVid(decodedFrames.first().bitmap, params)
                    }
                }
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

        // Static PNG / JPEG / AVIF (API 28+ / 31+ ImageDecoder handles AVIF too)
        val bitmap = decodeSingleBitmap(bytes) ?: return emptyList()
        return listOf(DecodedFrame(bitmap, 100))
    }

    private fun decodeSingleBitmap(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
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

    /**
     * Static WebP optimization using lossless check first, then iterative binary search
     * over quality [qualityMin..qualityMax] before ever touching inner resolution.
     */
    private fun convertStatic(source: Bitmap, params: ConverterParams): ConversionOutcome {
        val maxBytes = minOf(params.imgSizeMax, MAX_STATIC_BYTES)
        val canvasBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, params.resMax)
        try {
            // 1. Try lossless first on API 30+ for pixel-perfect fidelity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val lossless = WebPContainer.encodeStaticWebP(canvasBitmap, 100, lossless = true)
                if (lossless.size <= maxBytes) {
                    val check = validateStatic(lossless)
                    if (check != null) {
                        return ConversionOutcome.Success(lossless, check, isAnimated = false, StickerConversionMode.STATIC)
                    }
                }
            }

            // 2. Iterative binary search over quality at full 512x512 resolution
            val bestFullRes = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes
            ) { q ->
                WebPContainer.encodeStaticWebP(canvasBitmap, q, lossless = false)
            }
            if (bestFullRes != null) {
                val check = validateStatic(bestFullRes)
                if (check != null) {
                    return ConversionOutcome.Success(bestFullRes, check, isAnimated = false, StickerConversionMode.STATIC)
                }
            }

            // 3. Fallback: power-curve inner resolution steps inside 512x512 transparent canvas
            val resCandidates = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resCandidates) {
                val compactBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, innerRes)
                try {
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes
                    ) { q ->
                        WebPContainer.encodeStaticWebP(compactBitmap, q, lossless = false)
                    }
                    if (encoded != null) {
                        val check = validateStatic(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = false, StickerConversionMode.STATIC)
                        }
                    }
                } finally {
                    compactBitmap.recycle()
                }
            }

            return ConversionOutcome.Failure("Could not compress static sticker under 100 KB")
        } finally {
            canvasBitmap.recycle()
        }
    }

    /**
     * Converts a static image into a genuine motionless Animated WebP ("fake-vid")
     * with >=2 identical keyframes so static stickers can live alongside animated
     * stickers inside a single WhatsApp animated sticker pack.
     */
    private fun convertFakeVid(source: Bitmap, params: ConverterParams): ConversionOutcome {
        val maxBytes = minOf(params.vidSizeMax, MAX_ANIMATED_BYTES)
        val canvasBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, params.resMax)
        try {
            // 1. Try lossless fake-vid first on API 30+ (2 identical frames of 500ms = 1000ms)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val lossless = WebPContainer.encodeFakeVidWebP(
                    bitmap = canvasBitmap,
                    width = STICKER_SIZE_PX,
                    height = STICKER_SIZE_PX,
                    quality = 100,
                    lossless = true,
                    frameCount = 2,
                    frameDurationMs = 500
                )
                if (lossless.size <= maxBytes) {
                    val check = validateAnimated(lossless)
                    if (check != null) {
                        return ConversionOutcome.Success(lossless, check, isAnimated = true, StickerConversionMode.FAKE_VID)
                    }
                }
            }

            // 2. Binary search highest lossy quality at full 512x512 resolution
            val bestFullRes = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes
            ) { q ->
                WebPContainer.encodeFakeVidWebP(
                    bitmap = canvasBitmap,
                    width = STICKER_SIZE_PX,
                    height = STICKER_SIZE_PX,
                    quality = q,
                    lossless = false,
                    frameCount = 2,
                    frameDurationMs = 500
                )
            }
            if (bestFullRes != null) {
                val check = validateAnimated(bestFullRes)
                if (check != null) {
                    return ConversionOutcome.Success(bestFullRes, check, isAnimated = true, StickerConversionMode.FAKE_VID)
                }
            }

            // 3. Last resort inner resolution adjustment inside 512x512 canvas
            val resCandidates = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resCandidates) {
                val compactBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, innerRes)
                try {
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes
                    ) { q ->
                        WebPContainer.encodeFakeVidWebP(
                            bitmap = compactBitmap,
                            width = STICKER_SIZE_PX,
                            height = STICKER_SIZE_PX,
                            quality = q,
                            lossless = false,
                            frameCount = 2,
                            frameDurationMs = 500
                        )
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.FAKE_VID)
                        }
                    }
                } finally {
                    compactBitmap.recycle()
                }
            }

            return ConversionOutcome.Failure("Could not encode fake-vid animated WebP under 500 KB")
        } finally {
            canvasBitmap.recycle()
        }
    }

    /**
     * Animated WebP optimization prioritizing:
     * 1) Visual fidelity & full FPS via iterative quality binary search
     * 2) Lossless consecutive duplicate frame merging
     * 3) Power-curve FPS resampling only if qualityMin at full FPS exceeds 500 KB
     * 4) Inner resolution adjustment as final fallback
     */
    private fun convertAnimated(
        rawFrames: List<DecodedFrame>,
        allowTrimOver10s: Boolean,
        params: ConverterParams
    ): ConversionOutcome {
        val maxBytes = minOf(params.vidSizeMax, MAX_ANIMATED_BYTES)

        // Normalize frame timings (>= 8 ms per frame)
        var frames = rawFrames.map { frame ->
            val safeDur = if (frame.durationMs < params.minFrameDurationMs) params.minFrameDurationMs else frame.durationMs
            frame.copy(durationMs = safeDur)
        }

        val totalDuration = frames.sumOf { it.durationMs }
        if (totalDuration > params.durationMaxMs) {
            if (!allowTrimOver10s) {
                return ConversionOutcome.NeedsTrimConfirmation(totalDuration)
            }
            frames = trimFramesToMaxDuration(frames, params.durationMaxMs)
        }

        // Fit every frame to 512x512 transparent canvas
        val scaledFrames = frames.map {
            DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, params.resMax), it.durationMs)
        }

        try {
            // Merge strictly identical adjacent frames (lossless size reduction, preserving >=2 frames)
            val dedupedFrames = deduplicateIdenticalFrames(scaledFrames)

            // Pass A: 100% of frames preserved, binary search quality [qualityMin..qualityMax]
            val passA = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes
            ) { q ->
                WebPContainer.encodeAnimatedWebP(dedupedFrames, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
            }
            if (passA != null) {
                val check = validateAnimated(passA)
                if (check != null) {
                    return ConversionOutcome.Success(passA, check, isAnimated = true, StickerConversionMode.ANIMATED)
                }
            }

            // Pass B: Iterative FPS reduction via time-based resampling (keeping total duration faithful)
            val sourceDuration = dedupedFrames.sumOf { it.durationMs }.coerceAtLeast(16)
            val sourceFps = ((dedupedFrames.size * 1000f) / sourceDuration).roundToInt()
                .coerceIn(params.fpsMin, params.fpsMax)

            if (dedupedFrames.size > 3 && sourceFps > params.fpsMin) {
                val fpsSteps = generatePowerCurveInts(sourceFps, params.fpsMin, params.fpsPower, steps = 4).drop(1)
                for (targetFps in fpsSteps) {
                    val resampled = resampleFramesByFps(dedupedFrames, targetFps)
                    if (resampled.size >= dedupedFrames.size) continue
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes
                    ) { q ->
                        WebPContainer.encodeAnimatedWebP(resampled, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                }
            }

            // Pass C: Last resort inner resolution reduction inside 512x512 transparent canvas
            val resSteps = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resSteps) {
                val innerScaled = frames.map {
                    DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
                }
                try {
                    val compact = if (innerScaled.size > 6) {
                        resampleFramesByFps(dedupedFramesFrom(innerScaled), params.fpsMin.coerceAtLeast(8))
                    } else {
                        dedupedFramesFrom(innerScaled)
                    }
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes
                    ) { q ->
                        WebPContainer.encodeAnimatedWebP(compact, STICKER_SIZE_PX, STICKER_SIZE_PX, q)
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                } finally {
                    innerScaled.forEach { it.bitmap.recycle() }
                }
            }

            return ConversionOutcome.Failure("Could not compress animated sticker under 500 KB")
        } finally {
            scaledFrames.forEach { it.bitmap.recycle() }
        }
    }

    /**
     * Binary searches quality in [minQ..maxQ] to find the highest quality whose output
     * byte array is <= [maxBytes]. Tests maxQ first (fast win) and minQ (early fail).
     */
    private inline fun binarySearchQuality(
        minQ: Int,
        maxQ: Int,
        steps: Int,
        maxBytes: Int,
        encode: (Int) -> ByteArray
    ): ByteArray? {
        val highBytes = encode(maxQ)
        if (highBytes.size <= maxBytes) return highBytes

        val lowBytes = encode(minQ)
        if (lowBytes.size > maxBytes) return null

        var bestBytes = lowBytes
        var low = minQ + 1
        var high = maxQ - 1
        var remainingSteps = steps

        while (low <= high && remainingSteps > 0) {
            val mid = (low + high) ushr 1
            val candidate = encode(mid)
            if (candidate.size <= maxBytes) {
                bestBytes = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
            remainingSteps--
        }
        return bestBytes
    }

    private fun generatePowerCurveInts(start: Int, end: Int, power: Float, steps: Int): List<Int> {
        if (steps <= 1 || start == end) return listOf(start)
        val out = LinkedHashSet<Int>()
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1).toFloat()
            val curved = t.pow(power)
            val value = (start + (end - start) * curved).roundToInt()
            out.add(value)
        }
        out.add(end)
        return out.toList()
    }

    private fun deduplicateIdenticalFrames(frames: List<DecodedFrame>): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val result = mutableListOf<DecodedFrame>()
        var current = frames.first()
        for (i in 1 until frames.size) {
            val next = frames[i]
            if (current.bitmap.sameAs(next.bitmap) && frames.size - result.size > 2) {
                current = current.copy(durationMs = current.durationMs + next.durationMs)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return if (result.size >= 2) result else frames.take(2)
    }

    private fun dedupedFramesFrom(frames: List<DecodedFrame>): List<DecodedFrame> =
        deduplicateIdenticalFrames(frames)

    private fun resampleFramesByFps(frames: List<DecodedFrame>, targetFps: Int): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val totalDuration = frames.sumOf { it.durationMs }.coerceAtLeast(16)
        val targetFrameDur = maxOf(MIN_FRAME_DURATION_MS, (1000f / targetFps).roundToInt())
        val targetCount = maxOf(2, (totalDuration.toFloat() / targetFrameDur).roundToInt())
        if (targetCount >= frames.size) return frames

        // Build cumulative end timestamps
        val endTimes = IntArray(frames.size)
        var acc = 0
        for (i in frames.indices) {
            acc += frames[i].durationMs
            endTimes[i] = acc
        }

        val out = mutableListOf<DecodedFrame>()
        val stepMs = totalDuration.toFloat() / targetCount.toFloat()
        var lastIdx = -1
        for (k in 0 until targetCount) {
            val sampleTime = (k * stepMs + stepMs * 0.5f).roundToInt().coerceIn(0, totalDuration - 1)
            var idx = 0
            while (idx < endTimes.size - 1 && sampleTime >= endTimes[idx]) {
                idx++
            }
            val dur = maxOf(MIN_FRAME_DURATION_MS, stepMs.roundToInt())
            if (idx == lastIdx && out.isNotEmpty() && (targetCount - k) > 1) {
                val prev = out.removeAt(out.lastIndex)
                out.add(prev.copy(durationMs = prev.durationMs + dur))
            } else {
                out.add(DecodedFrame(frames[idx].bitmap, dur))
                lastIdx = idx
            }
        }
        // Clamp total duration <= 10s
        val clampedTotal = out.sumOf { it.durationMs }
        return if (clampedTotal > MAX_ANIMATION_DURATION_MS) {
            trimFramesToMaxDuration(out, MAX_ANIMATION_DURATION_MS)
        } else if (out.size >= 2) {
            out
        } else {
            frames.take(2)
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

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + destW, top + destH), paint)
        return result
    }
}

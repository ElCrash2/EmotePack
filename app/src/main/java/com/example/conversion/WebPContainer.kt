package com.example.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WebPInspectionResult(
    val isValidWebP: Boolean,
    val isAnimated: Boolean,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val frameCount: Int,
    val totalDurationMs: Int,
    val minFrameDurationMs: Int,
    val fileSizeBytes: Int
)

/**
 * Pure Kotlin RFC 9649 WebP RIFF container parser, animated WebP frame decoder,
 * animated WebP muxer (using Android framework's libwebp static frame encoder per keyframe),
 * fake-vid motionless animated WebP builder, and strict WhatsApp sticker validator.
 */
object WebPContainer {

    private const val VP8X_FLAG_ALPHA = 0x10
    private const val VP8X_FLAG_ANIMATION = 0x02

    private const val ANMF_FLAG_DISPOSE_BG = 0x01
    private const val ANMF_FLAG_NO_BLEND = 0x02

    fun isWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
    }

    fun inspect(bytes: ByteArray): WebPInspectionResult {
        if (!isWebP(bytes)) {
            return WebPInspectionResult(false, false, 0, 0, false, 0, 0, 0, bytes.size)
        }

        var width = 0
        var height = 0
        var hasAlpha = false
        var isAnimated = false
        var frameCount = 0
        var totalDuration = 0
        var minFrameDuration = Int.MAX_VALUE

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > bytes.size) break

            when (fourCC) {
                "VP8X" -> {
                    if (chunkSize >= 10) {
                        val flags = bytes[dataOffset].toInt() and 0xFF
                        hasAlpha = (flags and VP8X_FLAG_ALPHA) != 0
                        isAnimated = (flags and VP8X_FLAG_ANIMATION) != 0
                        width = readUInt24LE(bytes, dataOffset + 4) + 1
                        height = readUInt24LE(bytes, dataOffset + 7) + 1
                    }
                }
                "VP8L" -> {
                    if (!isAnimated && chunkSize >= 5 && bytes[dataOffset] == 0x2F.toByte()) {
                        val bits = readUInt32LE(bytes, dataOffset + 1)
                        if (width == 0) width = ((bits and 0x3FFF).toInt()) + 1
                        if (height == 0) height = (((bits ushr 14) and 0x3FFF).toInt()) + 1
                        hasAlpha = ((bits ushr 28) and 0x01) != 0L
                    }
                    if (!isAnimated) frameCount = maxOf(frameCount, 1)
                }
                "VP8 " -> {
                    if (!isAnimated && chunkSize >= 10 &&
                        bytes[dataOffset + 3] == 0x9D.toByte() &&
                        bytes[dataOffset + 4] == 0x01.toByte() &&
                        bytes[dataOffset + 5] == 0x2A.toByte()
                    ) {
                        val w = readUInt16LE(bytes, dataOffset + 6) and 0x3FFF
                        val h = readUInt16LE(bytes, dataOffset + 8) and 0x3FFF
                        if (width == 0) width = w
                        if (height == 0) height = h
                    }
                    if (!isAnimated) frameCount = maxOf(frameCount, 1)
                }
                "ALPH" -> hasAlpha = true
                "ANMF" -> {
                    if (chunkSize >= 16) {
                        isAnimated = true
                        frameCount++
                        val dur = readUInt24LE(bytes, dataOffset + 12)
                        totalDuration += dur
                        if (dur < minFrameDuration) minFrameDuration = dur
                    }
                }
            }

            offset = dataOffset + chunkSize + (chunkSize and 1)
        }

        if (frameCount == 0 && width > 0 && height > 0) frameCount = 1
        if (minFrameDuration == Int.MAX_VALUE) minFrameDuration = 0

        return WebPInspectionResult(
            isValidWebP = width > 0 && height > 0,
            isAnimated = isAnimated,
            width = width,
            height = height,
            hasAlpha = hasAlpha,
            frameCount = frameCount,
            totalDurationMs = totalDuration,
            minFrameDurationMs = minFrameDuration,
            fileSizeBytes = bytes.size
        )
    }

    /**
     * Decodes every frame of an animated WebP into full-canvas ARGB_8888 Bitmaps
     * while honoring frame X/Y offsets, alpha blending, and disposal-to-background.
     */
    fun decodeAnimatedFrames(bytes: ByteArray): List<DecodedFrame> {
        val inspection = inspect(bytes)
        if (!inspection.isValidWebP) return emptyList()
        if (!inspection.isAnimated) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
            } ?: return emptyList()
            return listOf(DecodedFrame(bmp, 100))
        }

        val canvasW = inspection.width
        val canvasH = inspection.height
        val canvasBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        val decoded = mutableListOf<DecodedFrame>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > bytes.size) break

            if (fourCC == "ANMF" && chunkSize > 16) {
                val frameX = readUInt24LE(bytes, dataOffset) * 2
                val frameY = readUInt24LE(bytes, dataOffset + 3) * 2
                val frameW = readUInt24LE(bytes, dataOffset + 6) + 1
                val frameH = readUInt24LE(bytes, dataOffset + 9) + 1
                val rawDuration = readUInt24LE(bytes, dataOffset + 12)
                val durationMs = if (rawDuration <= 0) 40 else rawDuration
                val flags = bytes[dataOffset + 15].toInt() and 0xFF
                val disposeBg = (flags and ANMF_FLAG_DISPOSE_BG) != 0
                val noBlend = (flags and ANMF_FLAG_NO_BLEND) != 0

                val subPayloadOffset = dataOffset + 16
                val subPayloadLen = chunkSize - 16
                val staticFrameWebp = wrapFrameAsStaticWebP(
                    bytes = bytes,
                    payloadOffset = subPayloadOffset,
                    payloadLen = subPayloadLen,
                    width = frameW,
                    height = frameH
                )

                val frameBitmap = BitmapFactory.decodeByteArray(staticFrameWebp, 0, staticFrameWebp.size)
                if (frameBitmap != null) {
                    val paint = if (noBlend) copyPaint else blendPaint
                    canvas.drawBitmap(frameBitmap, frameX.toFloat(), frameY.toFloat(), paint)
                    frameBitmap.recycle()

                    decoded.add(DecodedFrame(canvasBitmap.copy(Bitmap.Config.ARGB_8888, false), durationMs))

                    if (disposeBg) {
                        canvas.drawRect(
                            frameX.toFloat(),
                            frameY.toFloat(),
                            (frameX + frameW).toFloat(),
                            (frameY + frameH).toFloat(),
                            clearPaint
                        )
                    }
                }
            }

            offset = dataOffset + chunkSize + (chunkSize and 1)
        }

        canvasBitmap.recycle()
        return decoded
    }

    /**
     * Encodes a list of 512x512 ARGB_8888 frames into a standards-compliant Animated WebP
     * where every frame is an independent keyframe (disposal=1, no-blend=1) to guarantee
     * crisp transparency and prevent WhatsApp black-line artifacts.
     */
    fun encodeAnimatedWebP(
        frames: List<DecodedFrame>,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean = false
    ): ByteArray {
        val anmfChunks = ArrayList<ByteArray>(frames.size)
        var anyAlpha = false
        for (frame in frames) {
            val staticBytes = encodeStaticWebP(frame.bitmap, quality, lossless)
            val payloadChunks = extractImageBitstreamChunks(staticBytes)
            if (containsAlphaPayload(payloadChunks)) {
                anyAlpha = true
            }
            val duration = maxOf(8, frame.durationMs)
            val anmf = buildAnmfChunk(
                width = width,
                height = height,
                durationMs = duration,
                anmfFlags = ANMF_FLAG_DISPOSE_BG or ANMF_FLAG_NO_BLEND,
                payloadChunks = payloadChunks
            )
            anmfChunks.add(anmf)
        }

        return assembleAnimatedRiff(width, height, anyAlpha, anmfChunks)
    }

    /**
     * Encodes a single static bitmap into a real Animated WebP ("fake-vid") containing
     * [frameCount] identical keyframes using NO_BLEND (no disposal clear) so the sticker
     * remains 100% visually motionless, stable on loop, and compatible with WhatsApp's
     * animated WebP decoder.
     */
    fun encodeFakeVidWebP(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean = false,
        frameCount: Int = 2,
        frameDurationMs: Int = 500
    ): ByteArray {
        val staticBytes = encodeStaticWebP(bitmap, quality, lossless)
        val payloadChunks = extractImageBitstreamChunks(staticBytes)
        val hasAlpha = containsAlphaPayload(payloadChunks)
        val count = maxOf(2, frameCount)
        val duration = maxOf(8, frameDurationMs)

        val anmfChunks = ArrayList<ByteArray>(count)
        val sharedAnmf = buildAnmfChunk(
            width = width,
            height = height,
            durationMs = duration,
            anmfFlags = ANMF_FLAG_NO_BLEND,
            payloadChunks = payloadChunks
        )
        repeat(count) {
            anmfChunks.add(sharedAnmf)
        }
        return assembleAnimatedRiff(width, height, hasAlpha, anmfChunks)
    }

    private fun containsAlphaPayload(payloadChunks: ByteArray): Boolean =
        containsFourCC(payloadChunks, 0, payloadChunks.size, "ALPH") ||
            containsFourCC(payloadChunks, 0, payloadChunks.size, "VP8L")

    private fun assembleAnimatedRiff(
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        anmfChunks: List<ByteArray>
    ): ByteArray {
        val bodyOut = ByteArrayOutputStream()
        val vp8xFlags = VP8X_FLAG_ANIMATION or if (hasAlpha) VP8X_FLAG_ALPHA else 0

        // VP8X (10 bytes payload -> 18 bytes total chunk)
        bodyOut.write("VP8X".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(bodyOut, 10)
        bodyOut.write(vp8xFlags)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        writeUInt24LE(bodyOut, width - 1)
        writeUInt24LE(bodyOut, height - 1)

        // ANIM (6 bytes payload: transparent BGRA 0x00000000, loopCount = 0 infinite)
        bodyOut.write("ANIM".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(bodyOut, 6)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)

        for (anmf in anmfChunks) {
            bodyOut.write(anmf)
        }

        val bodyBytes = bodyOut.toByteArray()
        val finalOut = ByteArrayOutputStream(12 + bodyBytes.size)
        finalOut.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(finalOut, 4 + bodyBytes.size)
        finalOut.write("WEBP".toByteArray(Charsets.US_ASCII))
        finalOut.write(bodyBytes)
        return finalOut.toByteArray()
    }

    @Suppress("DEPRECATION")
    fun encodeStaticWebP(bitmap: Bitmap, quality: Int, lossless: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(0, 100), out)
        return out.toByteArray()
    }

    private fun wrapFrameAsStaticWebP(
        bytes: ByteArray,
        payloadOffset: Int,
        payloadLen: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val hasAlph = containsFourCC(bytes, payloadOffset, payloadLen, "ALPH")
        val hasVp8L = containsFourCC(bytes, payloadOffset, payloadLen, "VP8L")
        val out = ByteArrayOutputStream(payloadLen + 32)
        val body = ByteArrayOutputStream(payloadLen + 20)

        if (hasAlph || !hasVp8L) {
            // Emit VP8X so ALPH + VP8 or VP8 is decoded cleanly with alpha
            body.write("VP8X".toByteArray(Charsets.US_ASCII))
            writeUInt32LE(body, 10)
            body.write(if (hasAlph) VP8X_FLAG_ALPHA else 0)
            body.write(0); body.write(0); body.write(0)
            writeUInt24LE(body, width - 1)
            writeUInt24LE(body, height - 1)
        }

        body.write(bytes, payloadOffset, payloadLen)
        val bodyBytes = body.toByteArray()

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(out, 4 + bodyBytes.size)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun containsFourCC(bytes: ByteArray, start: Int, len: Int, target: String): Boolean {
        var offset = start
        val end = start + len
        while (offset + 8 <= end) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            if (fourCC == target) return true
            if (chunkSize < 0) break
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        return false
    }

    /**
     * Extracts ALPH, VP8, and VP8L chunks from a static WebP (stripping RIFF/VP8X/ICCP/EXIF).
     */
    private fun extractImageBitstreamChunks(staticWebP: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(staticWebP.size)
        var offset = 12
        while (offset + 8 <= staticWebP.size) {
            val fourCC = String(staticWebP, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(staticWebP, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > staticWebP.size) break

            if (fourCC == "ALPH" || fourCC == "VP8 " || fourCC == "VP8L") {
                val paddedSize = chunkSize + (chunkSize and 1)
                out.write(staticWebP, offset, 8 + chunkSize)
                if ((chunkSize and 1) == 1) out.write(0)
                offset = dataOffset + paddedSize
                continue
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        return out.toByteArray()
    }

    private fun buildAnmfChunk(
        width: Int,
        height: Int,
        durationMs: Int,
        anmfFlags: Int,
        payloadChunks: ByteArray
    ): ByteArray {
        val chunkPayloadSize = 16 + payloadChunks.size
        val out = ByteArrayOutputStream(8 + chunkPayloadSize + 1)
        out.write("ANMF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(out, chunkPayloadSize)
        writeUInt24LE(out, 0) // Frame X / 2 = 0
        writeUInt24LE(out, 0) // Frame Y / 2 = 0
        writeUInt24LE(out, width - 1)
        writeUInt24LE(out, height - 1)
        writeUInt24LE(out, durationMs)
        out.write(anmfFlags)
        out.write(payloadChunks)
        if ((chunkPayloadSize and 1) == 1) out.write(0)
        return out.toByteArray()
    }

    private fun readUInt16LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16)

    private fun readUInt32LE(b: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun writeUInt24LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
    }

    private fun writeUInt32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}

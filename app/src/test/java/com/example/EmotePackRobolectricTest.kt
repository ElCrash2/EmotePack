package com.example

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.conversion.ConversionOutcome
import com.example.conversion.DecodedFrame
import com.example.conversion.StickerConverter
import com.example.conversion.WebPContainer
import com.example.data.local.AppDatabase
import com.example.data.local.CollectionStatus
import com.example.data.local.ItemConversionStatus
import com.example.data.local.StickerCollectionEntity
import com.example.data.local.StickerItemEntity
import com.example.data.local.WhatsAppSubpackEntity
import com.example.data.model.SearchOriginType
import com.example.ui.viewmodel.HomeViewModel
import com.example.whatsapp.StickerContentProvider
import com.example.whatsapp.SubpackBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmotePackRobolectricTest {

    @Test
    fun `localization switches cleanly between Spanish and English`() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        val esConfig = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
        val esContext = base.createConfigurationContext(esConfig)
        assertEquals("Buscar streamer", esContext.getString(R.string.search_streamer_label))
        assertEquals("Buscar emote", esContext.getString(R.string.search_emote_label))
        assertEquals("Mis packs de stickers", esContext.getString(R.string.nav_my_packs))

        val enConfig = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val enContext = base.createConfigurationContext(enConfig)
        assertEquals("Search streamer", enContext.getString(R.string.search_streamer_label))
        assertEquals("Search emote", enContext.getString(R.string.search_emote_label))
        assertEquals("My Sticker Packs", enContext.getString(R.string.nav_my_packs))
    }

    @Test
    fun `home view model enforces mutually exclusive dual search fields`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = HomeViewModel(app)

        vm.updateStreamerQuery("Rubius")
        assertEquals(SearchOriginType.STREAMER, vm.uiState.value.activeMode)
        assertEquals("Rubius", vm.uiState.value.streamerQuery)
        assertEquals("", vm.uiState.value.emoteQuery)

        vm.onEmoteFieldActivated()
        assertEquals(SearchOriginType.EMOTE, vm.uiState.value.activeMode)
        assertEquals("", vm.uiState.value.streamerQuery)

        vm.updateEmoteQuery("peepoHappy")
        assertEquals("peepoHappy", vm.uiState.value.emoteQuery)
        assertEquals("", vm.uiState.value.streamerQuery)

        vm.onStreamerFieldActivated()
        assertEquals(SearchOriginType.STREAMER, vm.uiState.value.activeMode)
        assertEquals("", vm.uiState.value.emoteQuery)
    }

    @Test
    fun `subpack builder balances chunks over 30 without leaving orphan subpacks under 3`() {
        val items32 = (1..32).map { "item_$it" }
        val chunks32 = SubpackBuilder.chunkStickers(items32)
        assertEquals(2, chunks32.size)
        assertEquals(29, chunks32[0].size)
        assertEquals(3, chunks32[1].size)

        val items31 = (1..31).map { "item_$it" }
        val chunks31 = SubpackBuilder.chunkStickers(items31)
        assertEquals(2, chunks31.size)
        assertEquals(28, chunks31[0].size)
        assertEquals(3, chunks31[1].size)

        val items15 = (1..15).map { "item_$it" }
        val chunks15 = SubpackBuilder.chunkStickers(items15)
        assertEquals(1, chunks15.size)
        assertEquals(15, chunks15[0].size)
    }

    @Test
    fun `static sticker conversion produces valid 512x512 WebP under 100 KB`() {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            val c = Canvas(this)
            val p = Paint().apply { color = Color.CYAN }
            c.drawCircle(100f, 50f, 40f, p)
        }
        val pngBytes = ByteArrayOutputStream().apply {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bmp.recycle()

        val outcome = StickerConverter.convert(pngBytes, expectAnimated = false, allowTrimOver10s = false)
        assertTrue(outcome is ConversionOutcome.Success)
        val success = outcome as ConversionOutcome.Success
        assertFalse(success.isAnimated)
        assertEquals(512, success.inspection.width)
        assertEquals(512, success.inspection.height)
        assertTrue(success.webpBytes.size <= StickerConverter.MAX_STATIC_BYTES)
        assertTrue(WebPContainer.isWebP(success.webpBytes))

        val tempFile = File.createTempFile("static_test_", ".webp").apply {
            writeBytes(success.webpBytes)
            deleteOnExit()
        }
        val trayBytes = StickerConverter.generateTrayIcon(tempFile)
        assertTrue(trayBytes != null && trayBytes.size <= StickerConverter.MAX_TRAY_BYTES)
    }

    @Test
    fun `animated webp muxer and converter enforce 512x512, 8ms min frame, and 10s trim prompt`() {
        val frame1 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val frame2 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val muxedNormal = WebPContainer.encodeAnimatedWebP(
            frames = listOf(DecodedFrame(frame1, 80), DecodedFrame(frame2, 80)),
            width = 128,
            height = 128,
            quality = 80
        )
        val infoNormal = WebPContainer.inspect(muxedNormal)
        assertTrue(infoNormal.isValidWebP)
        assertTrue(infoNormal.isAnimated)
        assertEquals(2, infoNormal.frameCount)
        assertEquals(160, infoNormal.totalDurationMs)

        // Over 10s animation (e.g. 3 frames x 4000ms = 12,000ms) must prompt user unless allowed
        val muxedLong = WebPContainer.encodeAnimatedWebP(
            frames = listOf(
                DecodedFrame(frame1, 4000),
                DecodedFrame(frame2, 4000),
                DecodedFrame(frame1, 4000)
            ),
            width = 128,
            height = 128,
            quality = 80
        )

        val needsTrim = StickerConverter.convert(muxedLong, expectAnimated = true, allowTrimOver10s = false)
        assertTrue(needsTrim is ConversionOutcome.NeedsTrimConfirmation)
        assertEquals(12000, (needsTrim as ConversionOutcome.NeedsTrimConfirmation).totalDurationMs)

        val trimmed = StickerConverter.convert(muxedLong, expectAnimated = true, allowTrimOver10s = true)
        assertTrue(trimmed is ConversionOutcome.Success)
        val trimmedSuccess = trimmed as ConversionOutcome.Success
        assertTrue(trimmedSuccess.isAnimated)
        assertEquals(512, trimmedSuccess.inspection.width)
        assertEquals(512, trimmedSuccess.inspection.height)
        assertTrue(trimmedSuccess.inspection.totalDurationMs <= 10_000)
        assertTrue(trimmedSuccess.inspection.minFrameDurationMs >= 8)
        assertTrue(trimmedSuccess.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        frame1.recycle()
        frame2.recycle()
    }

    @Test
    fun `sticker content provider exposes ready subpacks and sticker rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)

        val colId = "col_test_1"
        val subpackId = "sp_test_anim_1"
        db.collectionDao().upsert(
            StickerCollectionEntity(
                id = colId,
                name = "Streamer Pack",
                createdAt = 1000L,
                updatedAt = 2000L,
                searchType = "STREAMER",
                searchQuery = "TestStreamer",
                streamerId = "u1",
                streamerName = "TestStreamer",
                streamerUsername = "teststreamer",
                streamerAvatarUrl = null,
                sourceEmoteSetId = "set1",
                trayIconPath = null,
                totalCount = 3,
                animatedCount = 3,
                staticCount = 0,
                status = CollectionStatus.READY
            )
        )
        db.subpackDao().insertAll(
            listOf(
                WhatsAppSubpackEntity(
                    id = subpackId,
                    collectionId = colId,
                    displayName = "Streamer Pack - Animated",
                    isAnimated = true,
                    trayIconPath = "/tmp/tray_$subpackId.png",
                    imageDataVersion = 1,
                    stickerCount = 3,
                    isWhatsAppReady = true,
                    addedToWhatsApp = false
                )
            )
        )
        db.stickerItemDao().insertAll(
            (1..3).map { i ->
                StickerItemEntity(
                    id = "item_$i",
                    collectionId = colId,
                    emoteId = "em_$i",
                    emoteName = "Emote$i",
                    sourceSetId = "set1",
                    sourceSetName = "Main",
                    isAnimated = true,
                    previewUrl = "https://cdn.7tv.app/emote/em_$i/2x.webp",
                    highResUrl = "https://cdn.7tv.app/emote/em_$i/4x.webp",
                    fallbackUrlsCsv = "",
                    finalWebpPath = "/tmp/sticker_$i.webp",
                    stickerFileName = "sticker_$i.webp",
                    fileSizeBytes = 12345L,
                    width = 512,
                    height = 512,
                    frameCount = 10,
                    durationMs = 800,
                    hasAlpha = true,
                    status = ItemConversionStatus.COMPLETED,
                    errorMessage = null,
                    subpackId = subpackId,
                    sortOrder = i
                )
            }
        )

        val controller = Robolectric.buildContentProvider(StickerContentProvider::class.java)
            .create()
        val provider = controller.get()
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY

        val metaUri = Uri.parse("content://$authority/metadata")
        provider.query(metaUri, null, null, null, null).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(subpackId, cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_IDENTIFIER_IN_QUERY)))
            assertEquals("Streamer Pack - Animated", cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_NAME_IN_QUERY)))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(StickerContentProvider.ANIMATED_STICKER_PACK)))
        }

        val stickersUri = Uri.parse("content://$authority/stickers/$subpackId")
        provider.query(stickersUri, null, null, null, null).use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("sticker_1.webp", cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_FILE_NAME_IN_QUERY)))
        }

        db.close()
    }
}

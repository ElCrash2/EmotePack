package com.example.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.BuildConfig

data class WhatsAppEnvironment(
    val consumerInstalled: Boolean,
    val consumerCompatible: Boolean,
    val businessInstalled: Boolean,
    val businessCompatible: Boolean
) {
    val anyCompatible: Boolean get() = (consumerInstalled && consumerCompatible) || (businessInstalled && businessCompatible)
    val anyInstalled: Boolean get() = consumerInstalled || businessInstalled
}

object WhatsAppPackExporter {

    const val WHATSAPP_CONSUMER_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    private const val STICKER_APP_QUERY_URL = "is_whitelisted"

    fun inspectEnvironment(context: Context): WhatsAppEnvironment {
        val pm = context.packageManager
        val consumerInstalled = isPackageInstalled(pm, WHATSAPP_CONSUMER_PACKAGE)
        val businessInstalled = isPackageInstalled(pm, WHATSAPP_BUSINESS_PACKAGE)

        val consumerCompat = consumerInstalled && canHandleStickerIntent(pm, WHATSAPP_CONSUMER_PACKAGE)
        val businessCompat = businessInstalled && canHandleStickerIntent(pm, WHATSAPP_BUSINESS_PACKAGE)

        return WhatsAppEnvironment(
            consumerInstalled = consumerInstalled,
            consumerCompatible = consumerCompat,
            businessInstalled = businessInstalled,
            businessCompatible = businessCompat
        )
    }

    fun buildAddToWhatsAppIntent(
        subpackId: String,
        subpackName: String,
        targetPackage: String? = null
    ): Intent {
        return Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_STICKER_PACK_ID, subpackId)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra(EXTRA_STICKER_PACK_NAME, subpackName)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    /**
     * Checks WhatsApp's official whitelist ContentProvider to verify whether a sticker pack
     * is currently added in WhatsApp or WhatsApp Business.
     */
    fun isWhitelistedInWhatsApp(context: Context, subpackId: String): Boolean {
        return isWhitelistedInPackage(context, WHATSAPP_CONSUMER_PACKAGE, subpackId) ||
            isWhitelistedInPackage(context, WHATSAPP_BUSINESS_PACKAGE, subpackId)
    }

    private fun isWhitelistedInPackage(context: Context, whatsappPkg: String, subpackId: String): Boolean {
        val pm = context.packageManager
        if (!isPackageInstalled(pm, whatsappPkg)) return false
        val authority = "$whatsappPkg.provider.sticker_whitelist_check"
        return try {
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveContentProvider(authority, 0)
            }
            if (provider == null) return false

            val queryUri = Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(STICKER_APP_QUERY_URL)
                .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
                .appendQueryParameter("identifier", subpackId)
                .build()

            context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0) == 1
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun canHandleStickerIntent(pm: PackageManager, pkg: String): Boolean {
        val intent = Intent(ACTION_ENABLE_STICKER_PACK).setPackage(pkg)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        return resolved != null
    }
}

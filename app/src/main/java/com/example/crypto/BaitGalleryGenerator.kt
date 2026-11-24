package com.example.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.example.camera.ExifStripper
import java.io.File

/**
 * BaitGalleryGenerator (Часть Д — SnapSafe "Poison Pill" Plausible Decoy Seeder):
 * Generates clean, metadata-free, ordinary-looking synthetic photos (shopping checklist,
 * evening sky gradient, minimalist desk wallpaper) and encrypts them into the DECOY vault
 * so that unlocking with a Duress / Decoy PIN reveals a believable everyday gallery instead of an empty vault.
 */
object BaitGalleryGenerator {

    data class BaitSeedResult(
        val createdCount: Int,
        val createdFiles: List<File>
    )

    fun seedDecoyBaitGallery(context: Context): BaitSeedResult {
        val created = mutableListOf<File>()
        val baits = listOf(
            buildShoppingChecklistBait(),
            buildEveningSkyBait(),
            buildCoffeeWallpaperBait()
        )
        for (bmp in baits) {
            try {
                val clean = ExifStripper.processBitmapClean(
                    sourceBitmap = bmp,
                    recycleSource = true,
                    preset = ExifStripper.CompressionPreset.HIGH_QUALITY,
                    antiPrnu = false
                )
                val file = GhostCryptoVault.encryptBytesToDecoyVault(
                    context = context,
                    plainBytes = clean.cleanBytes,
                    prefix = GhostCryptoVault.MEDIA_PHOTO
                )
                clean.cleanBytes.fill(0)
                if (file != null) {
                    created.add(file)
                }
            } catch (_: Exception) {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        return BaitSeedResult(createdCount = created.size, createdFiles = created)
    }

    private fun buildShoppingChecklistBait(): Bitmap {
        val w = 720
        val h = 960
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(245, 242, 232))

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 222, 235)
            strokeWidth = 2f
        }
        for (y in 120..900 step 56) {
            c.drawLine(48f, y.toFloat(), (w - 48).toFloat(), y.toFloat(), linePaint)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 48, 56)
            textSize = 42f
            isFakeBoldText = true
        }
        c.drawText("Список на неделю", 64f, 96f, titlePaint)

        val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 72, 82)
            textSize = 30f
        }
        val items = listOf(
            "• Хлеб, масло, сыр",
            "• Гречка 2 уп., рис",
            "• Кофе молотый",
            "• Яблоки / бананы",
            "• Батарейки AA (4 шт.)",
            "• Зарядный кабель Type-C"
        )
        items.forEachIndexed { idx, txt ->
            c.drawText(txt, 68f, 170f + idx * 56f, itemPaint)
        }
        return bmp
    }

    private fun buildEveningSkyBait(): Bitmap {
        val w = 960
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.rgb(28, 58, 110),
                    Color.rgb(112, 92, 148),
                    Color.rgb(238, 142, 92)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), skyPaint)

        val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 24, 34)
        }
        c.drawRect(0f, h * 0.78f, w.toFloat(), h.toFloat(), horizonPaint)
        return bmp
    }

    private fun buildCoffeeWallpaperBait(): Bitmap {
        val w = 720
        val h = 960
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(46, 34, 28),
                Color.rgb(92, 68, 54),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val cupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(228, 220, 210)
            style = Paint.Style.STROKE
            strokeWidth = 18f
        }
        c.drawCircle(w * 0.5f, h * 0.5f, 150f, cupPaint)

        val cremaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 128, 78)
        }
        c.drawCircle(w * 0.5f, h * 0.5f, 128f, cremaPaint)
        return bmp
    }
}

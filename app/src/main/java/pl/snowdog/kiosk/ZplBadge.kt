package pl.snowdog.kiosk

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

internal object ZplBadge {
    const val DPI = 300
    private const val WIDTH_MM = 57.1
    private const val LENGTH_MM = 76.0
    val widthDots = (WIDTH_MM * DPI / 25.4).roundToInt()
    val lengthDots = (LENGTH_MM * DPI / 25.4).roundToInt()
    private const val HEX = "0123456789ABCDEF"

    fun render(cacheDir: File, pdf: ByteArray): ByteArray {
        val file = File.createTempFile("badge-", ".pdf", cacheDir)
        try {
            file.writeBytes(pdf)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    check(renderer.pageCount == 1) { "invalid_badge_page_count" }
                    renderer.openPage(0).use { page ->
                        return rasterize(page)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun rasterize(page: PdfRenderer.Page): ByteArray {
        val image = Bitmap.createBitmap(widthDots, lengthDots, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.WHITE)
            // Rotate landscape artwork clockwise to match the stock's narrower print head.
            val matrix = Matrix().apply {
                setScale(lengthDots.toFloat() / page.width, widthDots.toFloat() / page.height)
                postRotate(90f)
                postTranslate(widthDots.toFloat(), 0f)
            }
            page.render(image, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return encode(image)
        } finally {
            image.recycle()
        }
    }

    private fun encode(image: Bitmap): ByteArray {
        val bytesPerRow = (image.width + 7) / 8
        val bytes = bytesPerRow * image.height
        val zpl = StringBuilder(96 + bytes * 2)
        zpl.append("^XA^PON^PW").append(image.width).append("^LL").append(image.height)
        zpl.append("^LH0,0^LT0^LS0^FO0,0^GFA,").append(bytes).append(',')
            .append(bytes).append(',').append(bytesPerRow).append(',')
        val pixels = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)
            for (start in 0 until image.width step 8) {
                var packed = 0
                for (x in start until minOf(start + 8, image.width)) {
                    val pixel = pixels[x]
                    val luminance = 299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)
                    if (luminance < 150_000) packed = packed or (0x80 shr (x - start))
                }
                zpl.append(HEX[packed shr 4]).append(HEX[packed and 0x0f])
            }
        }
        zpl.append("^FS^PQ1^XZ\n")
        return zpl.toString().toByteArray(Charsets.US_ASCII)
    }
}

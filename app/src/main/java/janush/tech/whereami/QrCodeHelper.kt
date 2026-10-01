package janush.tech.whereami

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

object QrCodeHelper {
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        if (content.isBlank()) {
            return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
                eraseColor(Color.WHITE)
            }
        }
        return try {
            val bitMatrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (_: Exception) {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
                eraseColor(Color.WHITE)
            }
        }
    }
}

package be.mv3d.tablet

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR-code als ImageBitmap (zwart/wit) voor bv. de kraancode in de zijbalk. */
fun qrBitmap(text: String, size: Int = 240): ImageBitmap? {
    if (text.isBlank()) return null
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size)
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        bmp.asImageBitmap()
    } catch (_: Exception) { null }
}

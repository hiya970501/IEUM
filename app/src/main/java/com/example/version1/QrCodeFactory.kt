package com.example.version1

import android.graphics.Bitmap
import com.example.afterglow.net.PrivateIpv4
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Generates the pairing code on-device. No URL or secret is sent to a QR service. */
object QrCodeFactory {
    fun create(content: String, size: Int = 640): Bitmap {
        require(content.length in 1..1024 && size in 128..2048)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

object PairingAddress {
    fun isPrivateIpv4(host: String): Boolean = PrivateIpv4.isCanonicalPrivate(host)

    fun landingUrl(host: String): String {
        require(isPrivateIpv4(host)) { "Use the PC's private Wi-Fi IPv4 address" }
        return "http://$host:8765/pair"
    }
}

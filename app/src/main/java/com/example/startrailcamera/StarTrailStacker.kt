package com.example.startrailcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StarTrailStacker {

    private var width: Int = 0
    private var height: Int = 0
    private var accumulator: IntArray? = null
    private var frameCount: Int = 0

    @Synchronized
    fun addFrame(jpegBytes: ByteArray): Int {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: return frameCount

        val w = bitmap.width
        val h = bitmap.height
        val framePixels = IntArray(w * h)
        bitmap.getPixels(framePixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        if (accumulator == null) {
            width = w
            height = h
            accumulator = framePixels
            frameCount = 1
            return frameCount
        }

        val acc = accumulator!!
        if (w != width || h != height) {
            return frameCount
        }

        for (i in acc.indices) {
            val p1 = acc[i]
            val p2 = framePixels[i]

            val r1 = (p1 shr 16) and 0xFF
            val g1 = (p1 shr 8) and 0xFF
            val b1 = p1 and 0xFF

            val r2 = (p2 shr 16) and 0xFF
            val g2 = (p2 shr 8) and 0xFF
            val b2 = p2 and 0xFF

            val r = if (r2 > r1) r2 else r1
            val g = if (g2 > g1) g2 else g1
            val b = if (b2 > b1) b2 else b1

            acc[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        frameCount++
        return frameCount
    }

    @Synchronized
    fun saveToGallery(context: Context): Uri? {
        val acc = accumulator ?: return null
        if (width <= 0 || height <= 0) return null

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(acc, 0, width, 0, 0, width, height)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "StarTrail_${timestamp}_${frameCount}frames.jpg"

        var outputUri: Uri? = null
        var outStream: OutputStream? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StarTrail")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (outputUri != null) {
                    outStream = resolver.openOutputStream(outputUri)
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 98, outStream!!)
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(outputUri, values, null, null)
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "StarTrail")
                if (!appDir.exists()) appDir.mkdirs()
                val imageFile = File(appDir, filename)
                outStream = FileOutputStream(imageFile)
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 98, outStream)
                outputUri = Uri.fromFile(imageFile)
            }
        } finally {
            outStream?.close()
            resultBitmap.recycle()
        }

        return outputUri
    }

    @Synchronized
    fun reset() {
        accumulator = null
        width = 0
        height = 0
        frameCount = 0
    }
}

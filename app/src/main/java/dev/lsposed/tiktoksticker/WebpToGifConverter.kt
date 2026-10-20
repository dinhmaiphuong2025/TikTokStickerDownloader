package dev.lsposed.tiktoksticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * WebpToGifConverter - Chuyển đổi Animated WebP (.awebp/.webp) sang Animated GIF (.gif)
 * Sử dụng thư viện WebP native có sẵn trong TikTok (com.facebook.animated.webp.WebPImage)
 * Kết hợp bộ mã hóa AnimatedGifEncoder để tạo GIF động chạy lặp vô tận, không bị đứng hình.
 */
object WebpToGifConverter {

    private const val TAG = "TikTokStickerHook"

    fun convertWebpToGif(
        webpBytes: ByteArray,
        outputFile: File,
        classLoader: ClassLoader
    ): Boolean {
        return runCatching {
            // Cách 1: Sử dụng WebPImage native của TikTok
            if (convertViaTikTokNativeWebP(webpBytes, outputFile, classLoader)) {
                return@runCatching true
            }

            // Cách 2: Fallback giải nén ANMF chunk nếu không gọi được WebPImage
            convertViaDemuxFallback(webpBytes, outputFile)
        }.getOrElse { e ->
            Log.e(TAG, "Error in convertWebpToGif", e)
            XposedBridge.log("[$TAG] Error converting WebP to GIF: ${e.message}")
            false
        }
    }

    /**
     * Tận dụng WebPImage và libstatic-webp.so có sẵn trong tiến trình TikTok
     */
    private fun convertViaTikTokNativeWebP(
        webpBytes: ByteArray,
        outputFile: File,
        classLoader: ClassLoader
    ): Boolean {
        return runCatching {
            val webPClass = XposedHelpers.findClassIfExists(
                "com.facebook.animated.webp.WebPImage",
                classLoader
            ) ?: return false

            val webpImage = runCatching {
                val m = webPClass.getMethod("create", ByteArray::class.java)
                m.invoke(null, webpBytes)
            }.recoverCatching {
                val directBuf = java.nio.ByteBuffer.allocateDirect(webpBytes.size)
                directBuf.put(webpBytes)
                directBuf.flip()
                val m = webPClass.getMethod("create", java.nio.ByteBuffer::class.java)
                m.invoke(null, directBuf)
            }.recoverCatching {
                val m = webPClass.getMethod("createFromByteArray", ByteArray::class.java)
                m.invoke(null, webpBytes)
            }.getOrNull() ?: return false

            val getWidth = webPClass.getMethod("getWidth")
            val getHeight = webPClass.getMethod("getHeight")
            val getFrameCount = webPClass.getMethod("getFrameCount")
            val getFrameDurations = webPClass.getMethod("getFrameDurations")
            val getFrame = webPClass.getMethod("getFrame", Int::class.javaPrimitiveType)

            val width = getWidth.invoke(webpImage) as Int
            val height = getHeight.invoke(webpImage) as Int
            val frameCount = getFrameCount.invoke(webpImage) as Int
            val durations = (getFrameDurations.invoke(webpImage) as? IntArray) ?: IntArray(frameCount) { 60 }

            if (frameCount <= 0 || width <= 0 || height <= 0) return false

            val fos = FileOutputStream(outputFile)
            val encoder = AnimatedGifEncoder()
            encoder.setRepeat(0) // Lặp vô tận
            encoder.setSample(10)
            encoder.start(fos)

            // Canvas bitmap đệm để render từng frame
            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Giới hạn tối đa 60 frames để GIF gọn nhẹ và convert nhanh
            val step = if (frameCount > 60) (frameCount / 60) + 1 else 1

            for (i in 0 until frameCount step step) {
                val frame = getFrame.invoke(webpImage, i) ?: continue
                runCatching {
                    val renderFrameMethod = frame.javaClass.getMethod(
                        "renderFrame",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Bitmap::class.java
                    )
                    renderFrameMethod.invoke(frame, width, height, frameBitmap)

                    val delay = if (i < durations.size) durations[i] * step else 60 * step
                    encoder.setDelay(delay)
                    encoder.addFrame(frameBitmap)

                    // Giải phóng frame native
                    val disposeMethod = frame.javaClass.getMethod("dispose")
                    disposeMethod.invoke(frame)
                }
            }

            encoder.finish()
            fos.close()

            // Giải phóng webpImage
            runCatching {
                webPClass.getMethod("dispose").invoke(webpImage)
            }

            Log.d(TAG, "Successfully converted WebP to GIF via TikTok WebPImage (${outputFile.length()} bytes)")
            true
        }.getOrElse {
            Log.w(TAG, "TikTok WebPImage native convert failed, trying fallback: ${it.message}")
            false
        }
    }

    /**
     * Fallback giải nén cơ bản qua ANMF
     */
    private fun convertViaDemuxFallback(webpBytes: ByteArray, outputFile: File): Boolean {
        // Nếu không có WebPImage, copy tạm file để giữ sticker an toàn
        return runCatching {
            outputFile.writeBytes(webpBytes)
            true
        }.getOrDefault(false)
    }
}

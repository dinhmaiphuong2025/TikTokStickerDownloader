package dev.lsposed.tiktoksticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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
            if (convertViaTikTokNativeWebP(webpBytes, outputFile, classLoader)) {
                return@runCatching true
            }
            false
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
        val webPClass = XposedHelpers.findClassIfExists(
            "com.facebook.animated.webp.WebPImage",
            classLoader
        ) ?: run {
            Log.w(TAG, "WebPImage class not found")
            return false
        }

        return runCatching {
            val webpImage = createWebPImage(webPClass, webpBytes) ?: run {
                Log.w(TAG, "Failed to create WebPImage from bytes")
                return false
            }

            val width = invokeInt(webpImage, "getWidth")
            val height = invokeInt(webpImage, "getHeight")
            val frameCount = invokeInt(webpImage, "getFrameCount")
            val durations = invokeIntArray(webpImage, "getFrameDurations")
                ?: IntArray(frameCount) { 60 }

            if (frameCount <= 0 || width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid dimensions: ${width}x$height, $frameCount frames")
                runCatching { invokeVoid(webpImage, "dispose") }
                return false
            }

            Log.d(TAG, "WebPImage decoded: ${width}x$height, $frameCount frames")

            val fos = FileOutputStream(outputFile)
            val encoder = AnimatedGifEncoder()
            encoder.setRepeat(0)
            encoder.setSample(10)
            encoder.start(fos)

            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBitmap)

            val step = if (frameCount > 60) (frameCount / 60) + 1 else 1
            var addedFrames = 0

            for (i in 0 until frameCount step step) {
                val frame = invokeFrame(webpImage, i) ?: continue

                runCatching {
                    // Xóa nền trong suốt trước mỗi frame để tránh lẫn frame cũ
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

                    val rendered = renderFrame(frame, width, height, frameBitmap)

                    val delay = if (i < durations.size && durations[i] > 0) durations[i] * step else 60 * step
                    encoder.setDelay(delay)
                    val ok = encoder.addFrame(frameBitmap)
                    if (ok) addedFrames++

                    // Giải phóng frame native
                    invokeVoid(frame, "dispose")
                }.onFailure { e ->
                    Log.e(TAG, "Frame $i failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            encoder.finish()
            fos.close()
            runCatching { invokeVoid(webpImage, "dispose") }

            if (addedFrames <= 0) {
                Log.w(TAG, "No frames rendered, output invalid")
                outputFile.delete()
                return false
            }

            Log.d(TAG, "Converted WebP -> GIF: $addedFrames frames, ${outputFile.length()} bytes")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Native WebP convert failed: ${e.javaClass.simpleName}: ${e.message}", e)
            outputFile.delete()
            false
        }
    }

    // =========================================================================
    // REFLECTION HELPERS
    // =========================================================================

    private fun createWebPImage(webPClass: Class<*>, webpBytes: ByteArray): Any? {
        // Thử create(byte[])
        runCatching {
            val m = findMethod(webPClass, "create", ByteArray::class.java)
            val r = m.invoke(null, webpBytes)
            if (r != null) return r
        }
        // Thử create(ByteBuffer) với buffer trực tiếp
        runCatching {
            val buf = java.nio.ByteBuffer.allocateDirect(webpBytes.size)
            buf.put(webpBytes)
            buf.flip()
            val m = findMethod(webPClass, "create", java.nio.ByteBuffer::class.java)
            val r = m.invoke(null, buf)
            if (r != null) return r
        }
        // Thử createFromByteArray(byte[]) (tên cũ)
        runCatching {
            val m = findMethod(webPClass, "createFromByteArray", ByteArray::class.java)
            val r = m.invoke(null, webpBytes)
            if (r != null) return r
        }
        return null
    }

    private fun invokeFrame(webpImage: Any, index: Int): Any? {
        return runCatching {
            val m = findMethod(webpImage.javaClass, "getFrame", Integer.TYPE)
            m.invoke(webpImage, index)
        }.getOrNull()
    }

    private fun renderFrame(frame: Any, width: Int, height: Int, bitmap: Bitmap): Boolean {
        // Thử renderFrame trước
        runCatching {
            val m = findMethod(frame.javaClass, "renderFrame", Integer.TYPE, Integer.TYPE, Bitmap::class.java)
            m.invoke(frame, width, height, bitmap)
            return true
        }
        // Thử nativeRenderFrame
        runCatching {
            val m = findMethod(frame.javaClass, "nativeRenderFrame", Integer.TYPE, Integer.TYPE, Bitmap::class.java)
            m.invoke(frame, width, height, bitmap)
            return true
        }
        return false
    }

    private fun invokeInt(target: Any, methodName: String): Int {
        return (findMethod(target.javaClass, methodName).invoke(target) as? Int) ?: 0
    }

    private fun invokeIntArray(target: Any, methodName: String): IntArray? {
        return findMethod(target.javaClass, methodName).invoke(target) as? IntArray
    }

    private fun invokeVoid(target: Any, methodName: String) {
        runCatching { findMethod(target.javaClass, methodName).invoke(target) }
    }

    /**
     * Tìm method qua toàn bộ phân cấp lớp và setAccessible(true)
     */
    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            runCatching {
                val m = current.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            }
            current = current.superclass
        }
        throw NoSuchMethodException("$name on ${clazz.name}")
    }
}

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

    private fun log(msg: String) {
        Log.d(TAG, msg)
        XposedBridge.log("[$TAG] $msg")
    }

    fun convertWebpToGif(
        webpBytes: ByteArray,
        outputFile: File,
        classLoader: ClassLoader
    ): Boolean {
        return runCatching {
            convertViaTikTokNativeWebP(webpBytes, outputFile, classLoader)
        }.getOrElse { e ->
            log("Error converting WebP to GIF: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun convertViaTikTokNativeWebP(
        webpBytes: ByteArray,
        outputFile: File,
        classLoader: ClassLoader
    ): Boolean {
        val webPClass = XposedHelpers.findClassIfExists(
            "com.facebook.animated.webp.WebPImage",
            classLoader
        ) ?: run {
            log("WebPImage class not found")
            return false
        }

        val webpImage = createWebPImage(webPClass, webpBytes) ?: run {
            log("Failed to create WebPImage from ${webpBytes.size} bytes")
            return false
        }

        return runCatching {
            val width = invokeInt(webpImage, "getWidth")
            val height = invokeInt(webpImage, "getHeight")
            val frameCount = invokeInt(webpImage, "getFrameCount")
            val durations = invokeIntArray(webpImage, "getFrameDurations")
                ?: IntArray(frameCount) { 60 }

            if (frameCount <= 0 || width <= 0 || height <= 0) {
                log("Invalid dimensions: ${width}x$height, $frameCount frames")
                runCatching { invokeVoid(webpImage, "dispose") }
                return false
            }

            log("WebPImage decoded: ${width}x$height, $frameCount frames, durations=${durations.size}")

            val fos = FileOutputStream(outputFile)
            val encoder = AnimatedGifEncoder()
            encoder.setRepeat(0)
            encoder.setSample(10)
            encoder.start(fos)

            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBitmap)

            val step = if (frameCount > 60) (frameCount / 60) + 1 else 1
            var addedFrames = 0
            var nullFrames = 0
            var renderFailures = 0

            for (i in 0 until frameCount step step) {
                val frame = invokeFrame(webpImage, i)
                if (frame == null) {
                    nullFrames++
                    continue
                }

                val rendered = renderFrame(frame, width, height, frameBitmap, canvas)
                if (!rendered) {
                    renderFailures++
                    continue
                }

                val delay = if (i < durations.size && durations[i] > 0) durations[i] * step else 60 * step
                encoder.setDelay(delay)
                if (encoder.addFrame(frameBitmap)) {
                    addedFrames++
                }
            }

            encoder.finish()
            fos.close()
            runCatching { invokeVoid(webpImage, "dispose") }

            log("Convert result: added=$addedFrames nullFrames=$nullFrames renderFail=$renderFailures total=$frameCount")

            if (addedFrames <= 1 && frameCount > 1) {
                log("Too few frames rendered ($addedFrames/$frameCount), output invalid")
                outputFile.delete()
                return false
            }

            log("Converted WebP -> GIF: $addedFrames frames, ${outputFile.length()} bytes")
            true
        }.getOrElse { e ->
            log("Native WebP convert failed: ${e.javaClass.simpleName}: ${e.message}")
            outputFile.delete()
            false
        }
    }

    // =========================================================================
    // REFLECTION HELPERS
    // =========================================================================

    private fun createWebPImage(webPClass: Class<*>, webpBytes: ByteArray): Any? {
        runCatching {
            val m = findMethod(webPClass, "create", ByteArray::class.java)
            val r = m.invoke(null, webpBytes)
            if (r != null) return r
        }
        runCatching {
            val buf = java.nio.ByteBuffer.allocateDirect(webpBytes.size)
            buf.put(webpBytes)
            buf.flip()
            val m = findMethod(webPClass, "create", java.nio.ByteBuffer::class.java)
            val r = m.invoke(null, buf)
            if (r != null) return r
        }
        runCatching {
            val m = findMethod(webPClass, "createFromByteArray", ByteArray::class.java)
            val r = m.invoke(null, webpBytes)
            if (r != null) return r
        }
        return null
    }

    /**
     * Thử tất cả biến thể getFrame(int) (bao gồm bridge method do covariant return type)
     */
    private fun invokeFrame(webpImage: Any, index: Int): Any? {
        for (m in webpImage.javaClass.declaredMethods) {
            if (m.name == "getFrame" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Integer.TYPE) {
                m.isAccessible = true
                val r = runCatching { m.invoke(webpImage, index) }.getOrNull()
                if (r != null) return r
            }
        }
        return runCatching {
            findMethod(webpImage.javaClass, "getFrame", Integer.TYPE).invoke(webpImage, index)
        }.getOrNull()
    }

    private fun renderFrame(frame: Any, width: Int, height: Int, bitmap: Bitmap, canvas: Canvas): Boolean {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        runCatching {
            val m = findMethod(frame.javaClass, "renderFrame", Integer.TYPE, Integer.TYPE, Bitmap::class.java)
            m.invoke(frame, width, height, bitmap)
            return true
        }
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

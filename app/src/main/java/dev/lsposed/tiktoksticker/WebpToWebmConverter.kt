package dev.lsposed.tiktoksticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * WebpToWebmConverter - Chuyển đổi Animated WebP (.awebp / .webp) sang Video WebM VP9 (.webm).
 * Định dạng chuẩn cho Telegram Video Stickers:
 * - Codec: VP9 (video/x-vnd.on2.vp9)
 * - Container: WebM (.webm)
 * - Kích thước: Cạnh lớn nhất đúng 512px (bội số của 16 để tương thích mọi phần cứng mã hóa)
 * - Tốc độ khung hình: Tối đa 30 FPS
 * - Thời lượng: Tối đa 3.0 giây
 * - Bitrate: 256 kbps (đảm bảo dung lượng file luôn < 256 KB theo chuẩn Telegram)
 */
object WebpToWebmConverter {

    private const val TAG = "TikTokStickerHook"
    private const val TARGET_MAX_DIM = 512
    private const val MAX_DURATION_US = 3_000_000L // 3 giây tối đa
    private const val BITRATE = 256 * 1024 // 256 kbps
    private const val TIMEOUT_US = 10_000L

    private fun log(msg: String) {
        Log.d(TAG, msg)
        XposedBridge.log("[$TAG] $msg")
    }

    fun convertWebpToWebm(
        webpBytes: ByteArray,
        outputFile: File,
        classLoader: ClassLoader
    ): Boolean {
        return runCatching {
            val webPClass = XposedHelpers.findClassIfExists(
                "com.facebook.animated.webp.WebPImage",
                classLoader
            ) ?: run {
                log("WebPImage class not found for WebM conversion")
                return false
            }

            val webpImage = createWebPImage(webPClass, webpBytes) ?: run {
                log("Failed to create WebPImage from ${webpBytes.size} bytes")
                return false
            }

            val width = invokeInt(webpImage, "getWidth")
            val height = invokeInt(webpImage, "getHeight")
            val frameCount = invokeInt(webpImage, "getFrameCount")
            val durations = invokeIntArray(webpImage, "getFrameDurations")
                ?: IntArray(frameCount) { 60 }

            if (frameCount <= 0 || width <= 0 || height <= 0) {
                log("Invalid dimensions for WebM: ${width}x$height, $frameCount frames")
                runCatching { invokeVoid(webpImage, "dispose") }
                return false
            }

            // Tính toán kích thước chuẩn Telegram (cạnh lớn 512px, chia hết cho 16)
            val (outW, outH) = if (width >= height) {
                val w = TARGET_MAX_DIM
                val h = (((TARGET_MAX_DIM.toDouble() * height / width).toInt() / 16) * 16).coerceAtLeast(16)
                w to h
            } else {
                val h = TARGET_MAX_DIM
                val w = (((TARGET_MAX_DIM.toDouble() * width / height).toInt() / 16) * 16).coerceAtLeast(16)
                w to h
            }

            log("Converting WebP to WebM VP9: ${width}x$height ($frameCount frames) -> ${outW}x$outH")

            // Tìm encoder VP9 (hoặc fallback VP8)
            val mimeType = MediaFormat.MIMETYPE_VIDEO_VP9
            val codec = runCatching {
                MediaCodec.createEncoderByType(mimeType)
            }.recoverCatching {
                log("VP9 encoder not found, falling back to VP8")
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP8)
            }.getOrNull() ?: run {
                log("No VP9/VP8 encoder available on device")
                runCatching { invokeVoid(webpImage, "dispose") }
                return false
            }

            val actualMime = if (codec.name.contains("vp8", ignoreCase = true)) {
                MediaFormat.MIMETYPE_VIDEO_VP8
            } else {
                MediaFormat.MIMETYPE_VIDEO_VP9
            }

            val colorFormat = selectColorFormat(codec, actualMime)
            val mediaFormat = MediaFormat.createVideoFormat(actualMime, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            if (outputFile.exists()) outputFile.delete()
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)

            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            fun drainEncoder(endOfStream: Boolean) {
                val waitTime = if (endOfStream) TIMEOUT_US else 0L
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, waitTime)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        Thread.sleep(5)
                        continue
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw IllegalStateException("Format changed twice")
                        val newFormat = codec.outputFormat
                        trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rawCanvas = Canvas(rawBitmap)
            val scaledBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val scaledCanvas = Canvas(scaledBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val srcRect = Rect(0, 0, width, height)
            val dstRect = Rect(0, 0, outW, outH)
            val argbPixels = IntArray(outW * outH)

            var ptsUs = 0L
            var encodedFramesCount = 0

            for (i in 0 until frameCount) {
                if (ptsUs >= MAX_DURATION_US) {
                    log("Reached max Telegram duration (3s), stopping at frame $i")
                    break
                }

                val frame = invokeFrame(webpImage, i) ?: continue

                // Render frame gốc
                rawCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val rendered = renderFrame(frame, width, height, rawBitmap)
                if (!rendered) continue

                // Vẽ scale lên kích thước 512px
                scaledCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                scaledCanvas.drawBitmap(rawBitmap, srcRect, dstRect, paint)

                scaledBitmap.getPixels(argbPixels, 0, outW, 0, 0, outW, outH)
                val yuvData = if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    argbToNV12(argbPixels, outW, outH)
                } else {
                    argbToI420(argbPixels, outW, outH)
                }

                var inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                while (inIndex < 0) {
                    drainEncoder(false)
                    inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                }

                val inBuffer = codec.getInputBuffer(inIndex)
                if (inBuffer != null) {
                    inBuffer.clear()
                    inBuffer.put(yuvData)
                    codec.queueInputBuffer(inIndex, 0, yuvData.size, ptsUs, 0)
                    encodedFramesCount++
                }

                val frameDurMs = if (i < durations.size && durations[i] > 0) durations[i] else 60
                ptsUs += frameDurMs * 1000L

                drainEncoder(false)
            }

            // Gửi cờ END_OF_STREAM
            var eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            while (eosIndex < 0) {
                drainEncoder(false)
                eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            codec.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(true)

            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
            runCatching { invokeVoid(webpImage, "dispose") }

            val success = outputFile.exists() && outputFile.length() > 500
            log("WebM conversion finished: frames=$encodedFramesCount size=${outputFile.length()} bytes, success=$success")
            success
        }.getOrElse { e ->
            log("WebM conversion failed: ${e.javaClass.simpleName}: ${e.message}")
            outputFile.delete()
            false
        }
    }

    // =========================================================================
    // COLOR FORMAT & YUV CONVERSION (BT.601)
    // =========================================================================

    private fun selectColorFormat(codec: MediaCodec, mimeType: String): Int {
        val caps = codec.codecInfo.getCapabilitiesForType(mimeType)
        val formats = caps.colorFormats
        return when {
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }
    }

    /**
     * Chuyển đổi ARGB sang I420 (YUV420Planar: Y plane, rồi U plane, rồi V plane)
     */
    private fun argbToI420(argb: IntArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val out = ByteArray(ySize + 2 * uvSize)

        var yIdx = 0
        var uIdx = ySize
        var vIdx = ySize + uvSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIdx++] = max(0, min(255, y)).toByte()

                if ((j and 1 == 0) && (i and 1 == 0)) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uIdx++] = max(0, min(255, u)).toByte()
                    out[vIdx++] = max(0, min(255, v)).toByte()
                }
            }
        }
        return out
    }

    /**
     * Chuyển đổi ARGB sang NV12 (YUV420SemiPlanar: Y plane, rồi đan xen UV)
     */
    private fun argbToNV12(argb: IntArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)

        var yIdx = 0
        var uvIdx = ySize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIdx++] = max(0, min(255, y)).toByte()

                if ((j and 1 == 0) && (i and 1 == 0)) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIdx++] = max(0, min(255, u)).toByte()
                    out[uvIdx++] = max(0, min(255, v)).toByte()
                }
            }
        }
        return out
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

    private fun renderFrame(frame: Any, width: Int, height: Int, bitmap: Bitmap): Boolean {
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

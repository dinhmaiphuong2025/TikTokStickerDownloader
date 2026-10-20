package dev.lsposed.tiktoksticker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * TikTokStickerDownloader - LSPosed Module
 * Hỗ trợ tải sticker động (.webp / .gif) từ bình luận TikTok, lưu sẵn định dạng GIF động dùng được trên Telegram,
 * hoặc gửi thẳng sang Telegram (hỗ trợ Nekogram). Giao diện BottomSheet chuẩn TikTok Tux Design.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "TikTokStickerHook"
        // Ngắn hơn ngưỡng long-press của TikTok (~400-500ms) để sheet của module kịp hiện trước menu bình luận
        private const val LONG_PRESS_TIMEOUT_MS = 350L

        // Danh sách target package TikTok
        private val TARGET_PACKAGES = setOf(
            "com.ss.android.ugc.trill",      // TikTok Châu Á / VN
            "com.zhiliaoapp.musically"       // TikTok Quốc tế
        )

        // Các package Telegram family (ưu tiên Nekogram vì người dùng đang dùng)
        private val TELEGRAM_PACKAGES = listOf(
            "tw.nekomimi.nekogram",   // Nekogram
            "org.telegram.messenger", // Telegram chính thức
            "org.telegram.plus",      // Plus Messenger
            "nekox.messenger"         // NekoX
        )

        // Bộ nhớ đệm WeakHashMap để tránh memory leak
        private val viewToUrlMap = Collections.synchronizedMap(WeakHashMap<Any, String>())
        private val controllerToUrlMap = Collections.synchronizedMap(WeakHashMap<Any, String>())

        // Lazy để tránh khởi tạo Handler trong <clinit> (chạy trước khi main looper sẵn sàng khi Zygote fork)
        private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

        private fun log(msg: String) {
            Log.d(TAG, msg)
            XposedBridge.log("[$TAG] $msg")
        }
    }

    private var appClassLoader: ClassLoader? = null

    // Trạng thái phát hiện nhấn giữ (không can thiệp cờ clickable của View để không phá cử chỉ vuốt)
    private var longPressPending = false
    private var downX = 0f
    private var downY = 0f
    private var downActivity: Activity? = null
    private var touchSlop = 8

    private val longPressRunnable = Runnable {
        if (longPressPending) {
            longPressPending = false
            val activity = downActivity ?: return@Runnable
            handleLongPress(activity, downX, downY)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        appClassLoader = lpparam.classLoader
        log("Initializing hook on target package: ${lpparam.packageName} (process: ${lpparam.processName})")

        // Tầng 1: Hook thư viện hiển thị ảnh để bắt URL sticker
        runCatching {
            hookSmartImageView(lpparam.classLoader)
        }.onFailure {
            log("Failed to hook SmartImageView: ${it.message}")
        }

        // Tầng 2: Phát hiện nhấn giữ qua Activity.dispatchTouchEvent (không phá vuốt)
        runCatching {
            hookActivityTouch(lpparam.classLoader)
        }.onFailure {
            log("Failed to hook Activity touch: ${it.message}")
        }
    }

    // =========================================================================
    // TẦNG 1: HOOK THƯ VIỆN HIỂN THỊ ẢNH (SMARTIMAGEVIEW & BYTE DANCE LIGHTEN)
    // =========================================================================

    private fun hookSmartImageView(classLoader: ClassLoader) {
        val smartImageViewClass = XposedHelpers.findClassIfExists(
            "com.bytedance.lighten.loader.SmartImageView",
            classLoader
        )

        if (smartImageViewClass == null) {
            log("SmartImageView class not found in current classLoader")
            return
        }

        log("Found SmartImageView: ${smartImageViewClass.name}, injecting hooks...")

        // Duyệt toàn bộ phân cấp lớp: SmartImageView -> LX/04nV (SimpleDraweeView) -> LX/04nU -> LX/04nT (DraweeView)
        var currentClass: Class<*>? = smartImageViewClass
        var hookedMethodsCount = 0

        while (currentClass != null && currentClass != ImageView::class.java && currentClass != View::class.java && currentClass != Any::class.java) {
            for (method in currentClass.declaredMethods) {
                if (Modifier.isStatic(method.modifiers)) continue

                // Hook tất cả phương thức nhận 1 tham số (bao gồm LIZ(LX/09bU), setImageURI, v.v.)
                if (method.parameterTypes.size == 1) {
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val view = param.thisObject as? View ?: return
                                val arg = param.args.firstOrNull() ?: return
                                val url = extractUrlFromObject(arg)
                                if (!url.isNullOrBlank()) {
                                    viewToUrlMap[view] = url
                                }
                            }
                        })
                        hookedMethodsCount++
                    }
                }
            }
            currentClass = currentClass.superclass
        }

        log("Successfully hooked $hookedMethodsCount loader methods across SmartImageView hierarchy")
    }

    // =========================================================================
    // TẦNG 2: PHÁT HIỆN NHẤN GIỮ KHÔNG PHÁ CỬ CHỈ VUỐT
    // =========================================================================

    private fun hookActivityTouch(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "dispatchTouchEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = param.thisObject as? Activity ?: return
                        val ev = param.args[0] as? MotionEvent ?: return
                        handleTouchEvent(activity, ev)
                    }
                }
            }
        )
        log("Hooked Activity.dispatchTouchEvent for non-intrusive long-press detection")
    }

    private fun handleTouchEvent(activity: Activity, ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downActivity = activity
                touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
                longPressPending = true
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressPending) {
                    val dx = abs(ev.rawX - downX)
                    val dy = abs(ev.rawY - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        cancelLongPress()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
    }

    private fun cancelLongPress() {
        if (longPressPending) {
            longPressPending = false
            mainHandler.removeCallbacks(longPressRunnable)
        }
    }

    private fun handleLongPress(activity: Activity, screenX: Float, screenY: Float) {
        val decor = activity.window?.decorView ?: return
        val stickerView = findStickerViewAt(decor, screenX, screenY)
        if (stickerView != null) {
            val url = extractUrlFromView(stickerView)
            if (!url.isNullOrBlank() && isStickerCandidate(stickerView, url)) {
                log("Long press detected on sticker: $url")
                showTikTokStyleBottomSheet(stickerView, url)
                return
            }
        }
        log("Long press fired but no sticker view at ($screenX, $screenY)")
    }

    /**
     * Tìm sticker view sâu nhất chứa điểm chạm (dùng global visible rect, không đụng cờ clickable)
     */
    private fun findStickerViewAt(view: View, screenX: Float, screenY: Float): View? {
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        if (!rect.contains(screenX.toInt(), screenY.toInt())) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val found = findStickerViewAt(child, screenX, screenY)
                if (found != null) return found
            }
        }

        // Nhận diện view sticker bằng 2 tín hiệu:
        // 1. View đã có URL bắt được trong viewToUrlMap (chính xác nhất)
        // 2. View thuộc phân cấp SmartImageView (đi ngược superclass, chống obfuscate tên class con)
        if (viewToUrlMap.containsKey(view) || isSmartImageViewLike(view)) {
            val url = extractUrlFromView(view)
            if (!url.isNullOrBlank() && isStickerCandidate(view, url)) {
                return view
            }
        }
        return null
    }

    /**
     * Kiểm tra view có thuộc cây kế thừa SmartImageView/DraweeView không (bất kể tên class con bị obfuscate)
     */
    private fun isSmartImageViewLike(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null && cls != View::class.java && cls != Any::class.java) {
            val n = cls.name
            if (n.contains("SmartImageView") || n.contains("04n") ||
                n.contains("DraweeView") || n.contains("lighten")) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    // =========================================================================
    // TRÍCH XUẤT URL
    // =========================================================================

    private fun extractUrlFromView(view: View): String? {
        viewToUrlMap[view]?.let { return it }

        runCatching {
            val getImageUriMethod = view.javaClass.getMethod("getImageUri")
            val uri = getImageUriMethod.invoke(view) as? Uri
            if (uri != null) {
                val s = uri.toString()
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    viewToUrlMap[view] = s
                    return s
                }
            }
        }

        runCatching {
            var cls: Class<*>? = view.javaClass
            while (cls != null && cls != View::class.java && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(view) ?: continue
                    val url = extractUrlFromObject(value)
                    if (!url.isNullOrBlank()) {
                        viewToUrlMap[view] = url
                        return url
                    }
                }
                cls = cls.superclass
            }
        }

        runCatching {
            val getController = view.javaClass.getMethod("getController")
            val controller = getController.invoke(view)
            if (controller != null) {
                controllerToUrlMap[controller]?.let { return it }
                val url = extractUrlFromObject(controller)
                if (!url.isNullOrBlank()) {
                    controllerToUrlMap[controller] = url
                    return url
                }
            }
        }

        val tag = view.tag
        if (tag != null) {
            val url = extractUrlFromObject(tag)
            if (!url.isNullOrBlank()) return url
        }

        return null
    }

    private fun extractUrlFromObject(obj: Any): String? {
        if (obj is Uri) {
            val s = obj.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) return s
        }
        if (obj is String) {
            if (obj.startsWith("http://") || obj.startsWith("https://")) return obj
        }

        runCatching {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                val v = field.get(obj) ?: continue
                when (v) {
                    is Uri -> {
                        val s = v.toString()
                        if (s.startsWith("http://") || s.startsWith("https://")) return s
                    }
                    is String -> {
                        if (v.startsWith("http://") || v.startsWith("https://")) return v
                    }
                    is List<*> -> {
                        val first = v.firstOrNull()?.toString()
                        if (!first.isNullOrBlank() && (first.startsWith("http://") || first.startsWith("https://"))) {
                            return first
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isStickerCandidate(view: View, url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("http")) return false
        if (lowerUrl.contains("avatar") || lowerUrl.contains("/user/")) return false

        val isCdn = lowerUrl.contains("tiktokcdn") ||
                lowerUrl.contains("byteimg") ||
                lowerUrl.contains("ibyteimg") ||
                lowerUrl.contains("byteoversea") ||
                lowerUrl.contains("tiktok")

        val isImageFormat = lowerUrl.contains(".webp") ||
                lowerUrl.contains(".gif") ||
                lowerUrl.contains(".png") ||
                lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".jpg")

        val isStickerKeyword = lowerUrl.contains("sticker") ||
                lowerUrl.contains("emoji") ||
                lowerUrl.contains("im_") ||
                lowerUrl.contains("animate") ||
                lowerUrl.contains("comment")

        return isCdn || isImageFormat || isStickerKeyword
    }

    // =========================================================================
    // GIAO DIỆN TIKTOK BOTTOM SHEET & TÁC VỤ
    // =========================================================================

    private fun showTikTokStyleBottomSheet(view: View, url: String) {
        val activity = getActivityFromView(view) ?: return
        val previewBitmap = capturePreviewBitmap(view)

        activity.runOnUiThread {
            runCatching {
                var currentSheet: TikTokBottomSheet? = null

                currentSheet = TikTokBottomSheet(
                    activity = activity,
                    previewBitmap = previewBitmap,
                    onDownloadOriginal = {
                        executeDownloadOriginal(activity, url)
                    },
                    onShareTelegramGif = { updateStatus ->
                        executeShareTelegramGif(activity, url, updateStatus) {
                            currentSheet?.dismiss()
                        }
                    },
                    onCopyUrl = {
                        copyUrlToClipboard(activity, url)
                    }
                )

                currentSheet.show()
            }.onFailure {
                log("Failed to show TikTokBottomSheet: ${it.message}")
            }
        }
    }

    /**
     * Chụp bitmap preview từ view mà KHÔNG đụng vào bounds của drawable gốc
     * (tránh làm sticker trong bình luận bị khoá kích thước)
     */
    private fun capturePreviewBitmap(view: View, maxSizePx: Int = 120): Bitmap? {
        return runCatching {
            val w = view.width.takeIf { it > 0 } ?: return@runCatching null
            val h = view.height.takeIf { it > 0 } ?: return@runCatching null
            val scale = min(maxSizePx.toFloat() / w, maxSizePx.toFloat() / h).coerceAtMost(1f)
            val outW = (w * scale).toInt().coerceAtLeast(1)
            val outH = (h * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            bmp
        }.getOrNull()
    }

    /**
     * Tải sticker gốc và lưu kèm bản GIF động (dùng được trên Telegram)
     */
    private fun executeDownloadOriginal(activity: Activity, url: String) {
        showToast(activity, "Đang tải sticker...")

        Thread {
            runCatching {
                val bytes = fetchUrlBytes(url) ?: run {
                    showToast(activity, "Không thể tải sticker từ CDN")
                    return@Thread
                }

                val isGif = isGifHeader(bytes) || url.contains(".gif", ignoreCase = true)
                val isAnimatedWebp = isAnimatedWebPHeader(bytes)
                val ext = when {
                    isGif -> "gif"
                    isAnimatedWebp || isWebpHeader(bytes) -> "webp"
                    isPngHeader(bytes) -> "png"
                    else -> "webp"
                }

                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "TikTokStickers"
                )
                if (!targetDir.exists()) targetDir.mkdirs()

                val baseName = "sticker_${System.currentTimeMillis()}"

                // 1. Lưu file gốc
                val originalFile = File(targetDir, "$baseName.$ext")
                originalFile.writeBytes(bytes)

                val savedFiles = mutableListOf(originalFile.absolutePath)

                // 2. Nếu là Animated WebP, chuyển thêm bản GIF động (Telegram-ready)
                if (isAnimatedWebp) {
                    val gifFile = File(targetDir, "$baseName.gif")
                    val cl = appClassLoader ?: activity.classLoader
                    val ok = WebpToGifConverter.convertWebpToGif(bytes, gifFile, cl)
                    if (ok && gifFile.length() > 100) {
                        savedFiles.add(gifFile.absolutePath)
                        log("Saved Telegram-ready GIF: ${gifFile.name} (${gifFile.length()} bytes)")
                    } else {
                        gifFile.delete()
                        log("GIF conversion produced invalid output, keeping only original")
                    }
                }

                // Quét vào thư viện
                for (path in savedFiles) {
                    val mime = if (path.endsWith(".gif")) "image/gif" else "image/webp"
                    MediaScannerConnection.scanFile(activity, arrayOf(path), arrayOf(mime), null)
                }

                val msg = if (savedFiles.size > 1) {
                    "Đã lưu file gốc + GIF động vào Pictures/TikTokStickers/"
                } else {
                    "Đã lưu vào Pictures/TikTokStickers/"
                }
                showToast(activity, msg)
            }.onFailure { err ->
                log("Error downloading original sticker: ${err.message}")
                showToast(activity, "Lỗi khi lưu sticker: ${err.localizedMessage}")
            }
        }.start()
    }

    /**
     * Chuyển đổi sang GIF động và gửi sang Telegram (Nekogram / bản chính thức / NekoX)
     */
    private fun executeShareTelegramGif(
        activity: Activity,
        url: String,
        updateStatus: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        Thread {
            runCatching {
                updateStatus("Đang tải dữ liệu sticker...")
                val bytes = fetchUrlBytes(url) ?: run {
                    showToast(activity, "Không thể tải sticker từ CDN")
                    activity.runOnUiThread { onComplete() }
                    return@Thread
                }

                val isAlreadyGif = isGifHeader(bytes) || url.contains(".gif", ignoreCase = true)
                val shareFile: File

                if (isAlreadyGif) {
                    shareFile = File(activity.cacheDir, "shared_${System.currentTimeMillis()}.gif")
                    shareFile.writeBytes(bytes)
                } else {
                    updateStatus("Đang chuyển đổi sang GIF động...")
                    val tempGif = File(activity.cacheDir, "telegram_sticker_${System.currentTimeMillis()}.gif")
                    val cl = appClassLoader ?: activity.classLoader
                    val ok = WebpToGifConverter.convertWebpToGif(bytes, tempGif, cl)
                    if (ok && tempGif.length() > 100) {
                        shareFile = tempGif
                    } else {
                        // Fallback: gửi file WebP gốc (Telegram vẫn hiển thị được ảnh)
                        shareFile = File(activity.cacheDir, "shared_${System.currentTimeMillis()}.webp")
                        shareFile.writeBytes(bytes)
                    }
                }

                activity.runOnUiThread {
                    onComplete()
                    val ext = if (shareFile.name.endsWith(".gif")) "gif" else "webp"
                    shareFileToTelegram(activity, shareFile, ext)
                }
            }.onFailure { err ->
                log("Error preparing Telegram sticker: ${err.message}")
                showToast(activity, "Lỗi: ${err.localizedMessage}")
                activity.runOnUiThread { onComplete() }
            }
        }.start()
    }

    private fun fetchUrlBytes(url: String): ByteArray? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                connect()
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun resolveTelegramPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in TELEGRAM_PACKAGES) {
            runCatching {
                pm.getPackageInfo(pkg, 0)
                return pkg
            }
        }
        return null
    }

    private fun shareFileToTelegram(activity: Activity, file: File, extension: String) {
        activity.runOnUiThread {
            runCatching {
                val mimeType = if (extension == "gif") "image/gif" else "image/webp"
                val fileUri = resolveShareableUri(activity, file)

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    clipData = ClipData.newRawUri("sticker", fileUri)
                }

                val tgPackage = resolveTelegramPackage(activity)
                if (tgPackage != null) {
                    sendIntent.setPackage(tgPackage)
                }

                runCatching {
                    activity.startActivity(sendIntent)
                }.onFailure {
                    sendIntent.setPackage(null)
                    val chooser = Intent.createChooser(sendIntent, "Chia sẻ sticker qua...")
                    activity.startActivity(chooser)
                }
            }.onFailure {
                log("Failed to share to Telegram: ${it.message}")
                showToast(activity, "Không thể mở Telegram: ${it.localizedMessage}")
            }
        }
    }

    private fun copyUrlToClipboard(activity: Activity, url: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("TikTok Sticker URL", url)
            clipboard.setPrimaryClip(clip)
            showToast(activity, "Đã sao chép liên kết vào bộ nhớ tạm!")
        }
    }

    // =========================================================================
    // TIỆN ÍCH HỖ TRỢ
    // =========================================================================

    private fun isWebpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                   bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val webp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                   bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return riff && webp
    }

    private fun isAnimatedWebPHeader(bytes: ByteArray): Boolean {
        if (!isWebpHeader(bytes) || bytes.size < 21) return false
        val vp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                   bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        if (!vp8x) return false
        val flags = bytes[20].toInt()
        return (flags and 0x02) != 0
    }

    private fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val magic = String(bytes, 0, 6, StandardCharsets.US_ASCII)
        return magic.startsWith("GIF87a") || magic.startsWith("GIF89a")
    }

    private fun isPngHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
               bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    private fun getActivityFromView(view: View): Activity? {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    private fun resolveShareableUri(context: Context, file: File): Uri {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PROVIDERS
            )
            val provider = packageInfo.providers?.firstOrNull {
                it.name.contains("FileProvider", ignoreCase = true) ||
                it.authority.contains("fileprovider", ignoreCase = true)
            }
            if (provider != null) {
                val fileProviderClass = Class.forName(provider.name, true, context.classLoader)
                val getUriMethod: Method = fileProviderClass.getMethod(
                    "getUriForFile",
                    Context::class.java,
                    String::class.java,
                    File::class.java
                )
                return@runCatching getUriMethod.invoke(null, context, provider.authority, file) as Uri
            }
            null
        }.getOrNull() ?: run {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            Uri.fromFile(file)
        }
    }

    private fun showToast(activity: Activity, message: String) {
        mainHandler.post {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}

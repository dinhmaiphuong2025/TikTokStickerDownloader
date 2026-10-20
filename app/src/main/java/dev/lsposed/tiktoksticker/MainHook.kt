package dev.lsposed.tiktoksticker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.WeakHashMap

/**
 * TikTokStickerDownloader - LSPosed Module
 * Hỗ trợ tải sticker động (.webp / .gif) từ bình luận TikTok hoặc chia sẻ sang Telegram.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "TikTokStickerHook"
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"

        // Danh sách target package
        private val TARGET_PACKAGES = setOf(
            "com.ss.android.ugc.trill",      // TikTok Châu Á / VN
            "com.zhiliaoapp.musically"       // TikTok Quốc tế
        )

        // Bộ nhớ đệm WeakHashMap để tránh memory leak
        private val viewToUrlMap = Collections.synchronizedMap(WeakHashMap<Any, String>())
        private val controllerToUrlMap = Collections.synchronizedMap(WeakHashMap<Any, String>())
        private val requestToUrlMap = Collections.synchronizedMap(WeakHashMap<Any, String>())
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        XposedBridge.log("[$TAG] Initializing hook on target package: ${lpparam.packageName} (process: ${lpparam.processName})")

        runCatching {
            hookFrescoPipeline(lpparam.classLoader)
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to hook Fresco pipeline: ${it.message}")
        }

        runCatching {
            hookByteDanceLighten(lpparam.classLoader)
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to hook ByteDance Lighten: ${it.message}")
        }

        runCatching {
            hookViewInteraction(lpparam.classLoader)
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to hook View interaction: ${it.message}")
        }
    }

    // =========================================================================
    // TẦNG 1: HOOK THƯ VIỆN HIỂN THỊ ẢNH (FRESCO & BYTEDANCE LIGHTEN)
    // =========================================================================

    /**
     * Hook thư viện Facebook Fresco: ImageRequestBuilder, DraweeController, SimpleDraweeView
     */
    private fun hookFrescoPipeline(classLoader: ClassLoader) {
        // 1. Hook com.facebook.imagepipeline.request.ImageRequestBuilder.newBuilderWithSource(Uri)
        val imageRequestBuilderClass = XposedHelpers.findClassIfExists(
            "com.facebook.imagepipeline.request.ImageRequestBuilder",
            classLoader
        )
        if (imageRequestBuilderClass != null) {
            XposedHelpers.findAndHookMethod(
                imageRequestBuilderClass,
                "newBuilderWithSource",
                Uri::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val uri = param.args[0] as? Uri ?: return
                            val builder = param.result ?: return
                            requestToUrlMap[builder] = uri.toString()
                        }
                    }
                }
            )

            // Hook build() của ImageRequestBuilder -> gán URL cho ImageRequest thành phẩm
            XposedHelpers.findAndHookMethod(
                imageRequestBuilderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val builder = param.thisObject ?: return
                            val imageRequest = param.result ?: return
                            requestToUrlMap[builder]?.let { url ->
                                requestToUrlMap[imageRequest] = url
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked ImageRequestBuilder successfully")
        }

        // 2. Hook AbstractDraweeControllerBuilder.setImageRequest & build()
        val controllerBuilderClass = XposedHelpers.findClassIfExists(
            "com.facebook.drawee.controller.AbstractDraweeControllerBuilder",
            classLoader
        )
        if (controllerBuilderClass != null) {
            XposedBridge.hookAllMethods(
                controllerBuilderClass,
                "setImageRequest",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val builder = param.thisObject ?: return
                            val request = param.args.firstOrNull() ?: return
                            val url = requestToUrlMap[request] ?: extractUriFromImageRequest(request)
                            if (url != null) {
                                requestToUrlMap[builder] = url
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                controllerBuilderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val builder = param.thisObject ?: return
                            val controller = param.result ?: return
                            requestToUrlMap[builder]?.let { url ->
                                controllerToUrlMap[controller] = url
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked AbstractDraweeControllerBuilder successfully")
        }

        // 3. Hook SimpleDraweeView & DraweeView
        val draweeViewClass = XposedHelpers.findClassIfExists(
            "com.facebook.drawee.view.DraweeView",
            classLoader
        )
        if (draweeViewClass != null) {
            // Hook setController(DraweeController)
            XposedBridge.hookAllMethods(
                draweeViewClass,
                "setController",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val view = param.thisObject as? View ?: return
                            val controller = param.args.firstOrNull() ?: return
                            controllerToUrlMap[controller]?.let { url ->
                                viewToUrlMap[view] = url
                            }
                            injectLongClickListener(view)
                        }
                    }
                }
            )
        }

        val simpleDraweeViewClass = XposedHelpers.findClassIfExists(
            "com.facebook.drawee.view.SimpleDraweeView",
            classLoader
        )
        if (simpleDraweeViewClass != null) {
            // Hook setImageURI
            XposedBridge.hookAllMethods(
                simpleDraweeViewClass,
                "setImageURI",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val view = param.thisObject as? View ?: return
                            for (arg in param.args) {
                                when (arg) {
                                    is Uri -> viewToUrlMap[view] = arg.toString()
                                    is String -> if (arg.startsWith("http")) viewToUrlMap[view] = arg
                                }
                            }
                            injectLongClickListener(view)
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked SimpleDraweeView successfully")
        }
    }

    /**
     * Hook ByteDance Lighten (thư viện tải ảnh nội bộ của ByteDance/TikTok)
     */
    private fun hookByteDanceLighten(classLoader: ClassLoader) {
        val smartImageViewClass = XposedHelpers.findClassIfExists(
            "com.bytedance.lighten.loader.SmartImageView",
            classLoader
        ) ?: return

        XposedBridge.log("[$TAG] Found SmartImageView, hooking loader methods...")

        // Hook tất cả phương thức nhận Uri/String/UrlModel để load ảnh
        smartImageViewClass.declaredMethods.forEach { method ->
            if (method.name in listOf("load", "setImageURI", "displayImage", "setController")) {
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val view = param.thisObject as? View ?: return
                                for (arg in param.args) {
                                    when (arg) {
                                        is Uri -> viewToUrlMap[view] = arg.toString()
                                        is String -> if (arg.startsWith("http")) viewToUrlMap[view] = arg
                                        else -> if (arg != null) {
                                            extractUrlFromByteDanceModel(arg)?.let { viewToUrlMap[view] = it }
                                        }
                                    }
                                }
                                injectLongClickListener(view)
                            }
                        }
                    })
                }
            }
        }
    }

    // =========================================================================
    // TẦNG 2: CAN THIỆP SỰ KIỆN TƯƠNG TÁC (LONG CLICK TRIGGER)
    // =========================================================================

    /**
     * Hook View.setOnLongClickListener để bọc listener gốc, đảm bảo không phá vỡ logic TikTok
     */
    private fun hookViewInteraction(classLoader: ClassLoader) {
        XposedBridge.hookAllMethods(
            View::class.java,
            "setOnLongClickListener",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.thisObject as? View ?: return
                        val className = view.javaClass.name
                        if (className.contains("DraweeView") || className.contains("SmartImageView") || className.contains("ImageView")) {
                            val originalListener = param.args.firstOrNull() as? View.OnLongClickListener
                            param.args[0] = View.OnLongClickListener { v ->
                                val stickerUrl = extractUrlFromView(v)
                                if (!stickerUrl.isNullOrBlank() && isStickerCandidate(v, stickerUrl)) {
                                    showStickerOptionsDialog(v, stickerUrl)
                                    true
                                } else {
                                    originalListener?.onLongClick(v) ?: false
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    /**
     * Tiêm OnLongClickListener vào View nếu chưa có listener được set
     */
    private fun injectLongClickListener(view: View) {
        runCatching {
            view.isLongClickable = true
            view.setOnLongClickListener { v ->
                val stickerUrl = extractUrlFromView(v)
                if (!stickerUrl.isNullOrBlank() && isStickerCandidate(v, stickerUrl)) {
                    showStickerOptionsDialog(v, stickerUrl)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Trích xuất URL từ View kết hợp WeakHashMap và Reflection fallback
     */
    private fun extractUrlFromView(view: View): String? {
        // 1. Tìm trong WeakHashMap trực tiếp
        viewToUrlMap[view]?.let { return it }

        // 2. Reflection fallback qua DraweeController
        runCatching {
            val getController = view.javaClass.getMethod("getController")
            val controller = getController.invoke(view)
            if (controller != null) {
                controllerToUrlMap[controller]?.let { return it }
                // Quét qua các trường của controller
                var currentClass: Class<*>? = controller.javaClass
                while (currentClass != null && currentClass != Any::class.java) {
                    for (field in currentClass.declaredFields) {
                        field.isAccessible = true
                        val value = field.get(controller) ?: continue
                        if (value is Uri) return value.toString()
                        if (value is String && (value.startsWith("http://") || value.startsWith("https://"))) return value
                        requestToUrlMap[value]?.let { return it }
                    }
                    currentClass = currentClass.superclass
                }
            }
        }

        // 3. Fallback qua Tag của View
        val tag = view.tag
        if (tag is String && (tag.startsWith("http://") || tag.startsWith("https://"))) {
            return tag
        } else if (tag != null) {
            extractUrlFromByteDanceModel(tag)?.let { return it }
        }

        return null
    }

    /**
     * Lọc ứng viên sticker dựa vào URL và kích thước View
     */
    private fun isStickerCandidate(view: View, url: String): Boolean {
        val lowerUrl = url.lowercase()
        val isCdnUrl = lowerUrl.contains("tiktokcdn.com") ||
                lowerUrl.contains("byteimg.com") ||
                lowerUrl.contains("ibyteimg.com") ||
                lowerUrl.contains("sticker") ||
                lowerUrl.contains("emoji") ||
                lowerUrl.contains(".webp") ||
                lowerUrl.contains(".gif")

        // Kích thước sticker trong bình luận thường >= 48dp, tránh nhầm lẫn với icon nhỏ hoặc avatar thu nhỏ
        val w = view.width
        val h = view.height
        val isProperSize = if (w > 0 && h > 0) (w >= 48 && h >= 48) else true

        return isCdnUrl && isProperSize
    }

    // =========================================================================
    // GIAO DIỆN & TÁC VỤ: DIALOG, DOWNLOAD, TELEGRAM SHARE
    // =========================================================================

    /**
     * Hiển thị AlertDialog với 3 tùy chọn hành động
     */
    private fun showStickerOptionsDialog(view: View, url: String) {
        val activity = getActivityFromView(view) ?: return
        activity.runOnUiThread {
            runCatching {
                val options = arrayOf(
                    "Tải Sticker gốc (.webp / .gif)",
                    "Chia sẻ sang Telegram",
                    "Sao chép URL gốc"
                )

                AlertDialog.Builder(activity)
                    .setTitle("TikTok Sticker Downloader")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> executeDownloadSticker(activity, url, shareToTelegram = false)
                            1 -> executeDownloadSticker(activity, url, shareToTelegram = true)
                            2 -> copyUrlToClipboard(activity, url)
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }.onFailure {
                XposedBridge.log("[$TAG] Failed to show dialog: ${it.message}")
            }
        }
    }

    /**
     * Tải sticker trên luồng phụ và lưu vào storage hoặc chuyển tiếp sang Telegram
     */
    private fun executeDownloadSticker(activity: Activity, url: String, shareToTelegram: Boolean) {
        showToast(activity, if (shareToTelegram) "Đang chuẩn bị sticker..." else "Đang tải sticker...")

        Thread {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    connect()
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    showToast(activity, "Lỗi HTTP: ${connection.responseCode}")
                    return@Thread
                }

                val bytes = connection.inputStream.use { it.readBytes() }
                val isAnimatedWebp = isAnimatedWebPHeader(bytes)
                val isGifFormat = isGifHeader(bytes)

                val extension = when {
                    isGifFormat -> "gif"
                    isAnimatedWebp || isWebpHeader(bytes) -> "webp"
                    isPngHeader(bytes) -> "png"
                    url.contains(".gif", ignoreCase = true) -> "gif"
                    else -> "webp"
                }

                if (shareToTelegram) {
                    // Lưu vào cache tạm thời của TikTok để share
                    val tempFile = File(activity.cacheDir, "shared_sticker_${System.currentTimeMillis()}.$extension")
                    tempFile.writeBytes(bytes)
                    shareFileToTelegram(activity, tempFile, extension)
                } else {
                    // Lưu vào thư mục công khai Pictures/TikTokStickers/
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "TikTokStickers"
                    )
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val destFile = File(targetDir, "sticker_${System.currentTimeMillis()}.$extension")
                    destFile.writeBytes(bytes)

                    val mimeType = if (extension == "gif") "image/gif" else "image/webp"

                    // Scan file vào MediaStore
                    MediaScannerConnection.scanFile(
                        activity,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType)
                    ) { path, uri ->
                        XposedBridge.log("[$TAG] Scanned into gallery: $path -> $uri")
                    }

                    showToast(activity, "Đã lưu vào Pictures/TikTokStickers/${destFile.name}")
                }
            }.onFailure { err ->
                XposedBridge.log("[$TAG] Error downloading sticker: ${err.message}")
                showToast(activity, "Lỗi khi xử lý sticker: ${err.localizedMessage}")
            }
        }.start()
    }

    /**
     * Chia sẻ file sang Telegram thông qua Intent ACTION_SEND
     */
    private fun shareFileToTelegram(activity: Activity, file: File, extension: String) {
        activity.runOnUiThread {
            runCatching {
                val mimeType = if (extension == "gif") "image/gif" else "image/webp"
                val fileUri = resolveShareableUri(activity, file)

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    setPackage(TELEGRAM_PACKAGE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Kiểm tra ứng dụng Telegram đã cài đặt chưa
                val isTelegramInstalled = runCatching {
                    activity.packageManager.getPackageInfo(TELEGRAM_PACKAGE, 0)
                    true
                }.getOrDefault(false)

                if (isTelegramInstalled) {
                    activity.startActivity(sendIntent)
                } else {
                    // Fallback: Mở trình chọn chia sẻ hệ thống nếu Telegram chính thức chưa cài đặt
                    sendIntent.setPackage(null)
                    val chooser = Intent.createChooser(sendIntent, "Chia sẻ sticker qua...")
                    activity.startActivity(chooser)
                }
            }.onFailure {
                XposedBridge.log("[$TAG] Failed to share to Telegram: ${it.message}")
                showToast(activity, "Không thể mở Telegram: ${it.localizedMessage}")
            }
        }
    }

    /**
     * Sao chép URL vào Clipboard
     */
    private fun copyUrlToClipboard(activity: Activity, url: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("TikTok Sticker URL", url)
            clipboard.setPrimaryClip(clip)
            showToast(activity, "Đã sao chép URL gốc vào clipboard!")
        }
    }

    // =========================================================================
    // TIỆN ÍCH HỖ TRỢ (HELPERS & BINARY HEADER PARSING)
    // =========================================================================

    /**
     * Kiểm tra header WebP chuẩn (RIFF .... WEBP)
     */
    private fun isWebpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                   bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val webp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                   bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return riff && webp
    }

    /**
     * Kiểm tra Animated WebP: chunk 'VP8X' và bit Animation (0x02) tại flags byte (offset 20)
     */
    private fun isAnimatedWebPHeader(bytes: ByteArray): Boolean {
        if (!isWebpHeader(bytes) || bytes.size < 21) return false
        val vp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                   bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        if (!vp8x) return false
        val flags = bytes[20].toInt()
        return (flags and 0x02) != 0
    }

    /**
     * Kiểm tra header định dạng GIF (GIF87a / GIF89a)
     */
    private fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val magic = String(bytes, 0, 6, StandardCharsets.US_ASCII)
        return magic.startsWith("GIF87a") || magic.startsWith("GIF89a")
    }

    /**
     * Kiểm tra header PNG
     */
    private fun isPngHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
               bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    /**
     * Tìm kiếm Activity từ View Context (duyệt qua ContextWrapper)
     */
    private fun getActivityFromView(view: View): Activity? {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /**
     * Lấy URI chia sẻ an toàn: tìm FileProvider của TikTok hoặc dùng StrictMode fallback
     */
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
            // Tạm thời mở StrictMode để cho phép chia sẻ file URI cục bộ nếu không tìm thấy FileProvider
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            Uri.fromFile(file)
        }
    }

    /**
     * Trích xuất URI từ đối tượng ImageRequest của Fresco bằng reflection
     */
    private fun extractUriFromImageRequest(request: Any): String? {
        return runCatching {
            val method = request.javaClass.getMethod("getSourceUri")
            (method.invoke(request) as? Uri)?.toString()
        }.getOrNull()
    }

    /**
     * Trích xuất URL từ model dữ liệu ByteDance (UrlModel / UrlStruct)
     */
    private fun extractUrlFromByteDanceModel(model: Any): String? {
        // Kiểm tra phương thức getUrlList() trả về List<String>
        runCatching {
            val method = model.javaClass.getMethod("getUrlList")
            val list = method.invoke(model) as? List<*>
            val first = list?.firstOrNull()?.toString()
            if (!first.isNullOrBlank() && first.startsWith("http")) return first
        }
        // Kiểm tra phương thức getUri() trả về String hoặc Uri
        runCatching {
            val method = model.javaClass.getMethod("getUri")
            val uriStr = method.invoke(model)?.toString()
            if (!uriStr.isNullOrBlank() && uriStr.startsWith("http")) return uriStr
        }
        return null
    }

    /**
     * Hiển thị Toast thông báo trên Main Thread
     */
    private fun showToast(activity: Activity, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}

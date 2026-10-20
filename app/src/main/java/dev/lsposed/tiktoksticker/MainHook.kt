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
import android.util.Log
import android.view.View
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

        private fun log(msg: String) {
            Log.d(TAG, msg)
            XposedBridge.log("[$TAG] $msg")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        log("Initializing hook on target package: ${lpparam.packageName} (process: ${lpparam.processName})")

        // Hook ByteDance Lighten (SmartImageView & superclasses LX/04nV...)
        runCatching {
            hookSmartImageView(lpparam.classLoader)
        }.onFailure {
            log("Failed to hook SmartImageView: ${it.message}")
        }

        // Can thiệp sự kiện tương tác
        runCatching {
            hookViewInteraction(lpparam.classLoader)
        }.onFailure {
            log("Failed to hook View interaction: ${it.message}")
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

        // 1. Hook tất cả constructor của SmartImageView để tiêm listener sớm
        runCatching {
            XposedBridge.hookAllConstructors(smartImageViewClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    injectInteraction(view)
                }
            })
        }

        // 2. Hook onAttachedToWindow
        runCatching {
            XposedBridge.hookAllMethods(smartImageViewClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    injectInteraction(view)
                }
            })
        }

        // 3. Duyệt toàn bộ phân cấp lớp: SmartImageView -> LX/04nV (SimpleDraweeView) -> LX/04nU -> LX/04nT (DraweeView)
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
                                    log("Captured URL from ${view.javaClass.simpleName}.${method.name}: $url")
                                }
                                injectInteraction(view)
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
    // TẦNG 2: CAN THIỆP SỰ KIỆN TƯƠNG TÁC (LONG CLICK TRIGGER)
    // =========================================================================

    private fun hookViewInteraction(classLoader: ClassLoader) {
        // Hook View.setOnLongClickListener để bọc listener gốc
        XposedBridge.hookAllMethods(
            View::class.java,
            "setOnLongClickListener",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.thisObject as? View ?: return
                        val className = view.javaClass.name

                        // Bọc listener nếu là SmartImageView, DraweeView hoặc ViewGroup (comment row)
                        val isCandidateView = className.contains("SmartImageView") ||
                                className.contains("DraweeView") ||
                                className.contains("04n") ||
                                view is ViewGroup

                        if (isCandidateView) {
                            val originalListener = param.args.firstOrNull() as? View.OnLongClickListener
                            param.args[0] = View.OnLongClickListener { v ->
                                // 1. Kiểm tra trực tiếp trên View được click
                                val stickerUrl = extractUrlFromView(v)
                                if (!stickerUrl.isNullOrBlank() && isStickerCandidate(v, stickerUrl)) {
                                    log("Intercepted long click on sticker view: $v, URL: $stickerUrl")
                                    showStickerOptionsDialog(v, stickerUrl)
                                    return@OnLongClickListener true
                                }

                                // 2. Nếu là ViewGroup (dòng bình luận), quét các view con tìm SmartImageView chứa sticker
                                if (v is ViewGroup) {
                                    val childSticker = findStickerViewInChildren(v)
                                    if (childSticker != null) {
                                        val childUrl = extractUrlFromView(childSticker)
                                        if (!childUrl.isNullOrBlank() && isStickerCandidate(childSticker, childUrl)) {
                                            log("Intercepted long click on comment parent containing sticker: $childUrl")
                                            showStickerOptionsDialog(childSticker, childUrl)
                                            return@OnLongClickListener true
                                        }
                                    }
                                }

                                originalListener?.onLongClick(v) ?: false
                            }
                        }
                    }
                }
            }
        )
    }

    /**
     * Tiêm OnLongClickListener chủ động vào View
     */
    private fun injectInteraction(view: View) {
        runCatching {
            view.isClickable = true
            view.isLongClickable = true
            view.setOnLongClickListener { v ->
                val stickerUrl = extractUrlFromView(v)
                if (!stickerUrl.isNullOrBlank() && isStickerCandidate(v, stickerUrl)) {
                    log("Long click triggered on injected listener: $stickerUrl")
                    showStickerOptionsDialog(v, stickerUrl)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Quét đệ quy ViewGroup để tìm SmartImageView chứa sticker
     */
    private fun findStickerViewInChildren(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = child.javaClass.name
            if (name.contains("SmartImageView") || name.contains("04n")) {
                val url = extractUrlFromView(child)
                if (!url.isNullOrBlank() && isStickerCandidate(child, url)) {
                    return child
                }
            } else if (child is ViewGroup) {
                val found = findStickerViewInChildren(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Trích xuất URL từ View kết hợp WeakHashMap, getImageUri() và Reflection fallback
     */
    private fun extractUrlFromView(view: View): String? {
        // 1. WeakHashMap lookup
        viewToUrlMap[view]?.let { return it }

        // 2. Gọi getImageUri() phản chiếu từ LX/04nV (SimpleDraweeView)
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

        // 3. Quét các trường của View và các lớp cha
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

        // 4. Controller lookup
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

        // 5. View tag
        val tag = view.tag
        if (tag != null) {
            val url = extractUrlFromObject(tag)
            if (!url.isNullOrBlank()) return url
        }

        return null
    }

    /**
     * Trích xuất URL từ bất kỳ đối tượng nào (Uri, String, Model, Controller, ImageRequest, Options)
     */
    private fun extractUrlFromObject(obj: Any): String? {
        if (obj is Uri) {
            val s = obj.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) return s
        }
        if (obj is String) {
            if (obj.startsWith("http://") || obj.startsWith("https://")) return obj
        }

        // Quét các trường của đối tượng
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

    /**
     * Lọc ứng viên sticker: phân biệt với avatar người dùng
     */
    private fun isStickerCandidate(view: View, url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("http")) return false

        // Loại trừ avatar profile trong comment
        if (lowerUrl.contains("avatar") || lowerUrl.contains("/user/")) {
            return false
        }

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
    // GIAO DIỆN & TÁC VỤ: DIALOG, DOWNLOAD, TELEGRAM SHARE
    // =========================================================================

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
                log("Failed to show dialog: ${it.message}")
            }
        }
    }

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
                    val tempFile = File(activity.cacheDir, "shared_sticker_${System.currentTimeMillis()}.$extension")
                    tempFile.writeBytes(bytes)
                    shareFileToTelegram(activity, tempFile, extension)
                } else {
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "TikTokStickers"
                    )
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val destFile = File(targetDir, "sticker_${System.currentTimeMillis()}.$extension")
                    destFile.writeBytes(bytes)

                    val mimeType = if (extension == "gif") "image/gif" else "image/webp"

                    MediaScannerConnection.scanFile(
                        activity,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType)
                    ) { path, uri ->
                        log("Scanned into gallery: $path -> $uri")
                    }

                    showToast(activity, "Đã lưu vào Pictures/TikTokStickers/${destFile.name}")
                }
            }.onFailure { err ->
                log("Error downloading sticker: ${err.message}")
                showToast(activity, "Lỗi khi xử lý sticker: ${err.localizedMessage}")
            }
        }.start()
    }

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

                val isTelegramInstalled = runCatching {
                    activity.packageManager.getPackageInfo(TELEGRAM_PACKAGE, 0)
                    true
                }.getOrDefault(false)

                if (isTelegramInstalled) {
                    activity.startActivity(sendIntent)
                } else {
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
            showToast(activity, "Đã sao chép URL gốc vào clipboard!")
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
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}

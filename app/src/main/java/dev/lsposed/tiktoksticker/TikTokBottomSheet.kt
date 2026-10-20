package dev.lsposed.tiktoksticker

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.min

/**
 * TikTokBottomSheet - Giao diện Bottom Sheet mô phỏng 100% phong cách Tux Design System của TikTok.
 * Tông màu Dark Obsidian (#161823), bo góc chuẩn 16dp, drag handle pill, badge màu nhận diện.
 */
class TikTokBottomSheet(
    private val activity: Activity,
    private val previewDrawable: Drawable?,
    private val onDownloadOriginal: () -> Unit,
    private val onShareTelegramGif: (updateStatus: (String) -> Unit) -> Unit,
    private val onCopyUrl: () -> Unit
) {

    private val dialog: Dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
    private val density = activity.resources.displayMetrics.density

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.attributes.gravity = Gravity.BOTTOM
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        val rootLayout = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#80000000")) // Dim 50%
            setOnClickListener { dialog.dismiss() }
        }

        // Card BottomSheet chính (màu #161823, bo góc trên 16dp)
        val sheetCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val cornerRadius = dp(16f).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161823"))
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius, // top-left
                    cornerRadius, cornerRadius, // top-right
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(16f), dp(10f), dp(16f), dp(20f))
            isClickable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        rootLayout.addView(sheetCard, cardParams)

        // 1. Drag Handle Pill (#383A44, 36dp x 4dp)
        val dragHandle = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#383A44"))
                cornerRadius = dp(2f).toFloat()
            }
        }
        val handleParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12f)
        }
        sheetCard.addView(dragHandle, handleParams)

        // 2. Header: Preview Thumbnail + Tiêu đề
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14f))
        }

        // Ảnh Preview Sticker (56x56 dp, bo góc 10dp)
        val thumbnailView = ImageView(activity).apply {
            val thumbCorner = dp(10f).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#20222C"))
                cornerRadius = thumbCorner
                setStroke(dp(1f), Color.parseColor("#2D303E"))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            createPreviewBitmap(previewDrawable, dp(44f))?.let { setImageBitmap(it) }
        }
        val thumbParams = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply {
            rightMargin = dp(14f)
        }
        headerLayout.addView(thumbnailView, thumbParams)

        // Tiêu đề và Subtitle
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(activity).apply {
            text = "Tùy chọn Sticker"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitleView = TextView(activity).apply {
            text = "TikTok Sticker Downloader"
            setTextColor(Color.parseColor("#8A8B91"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(subtitleView)
        headerLayout.addView(titleColumn)

        sheetCard.addView(headerLayout)

        // Phân tách nhẹ
        val divider1 = View(activity).apply {
            setBackgroundColor(Color.parseColor("#222430"))
        }
        sheetCard.addView(divider1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
            bottomMargin = dp(10f)
        })

        // Khung trạng thái loading khi đang convert
        val progressLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1E29"))
                cornerRadius = dp(10f).toFloat()
            }
        }
        val progressBar = ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#FE2C55"))
        }
        val progressText = TextView(activity).apply {
            text = "Đang xử lý sticker..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12f), 0, 0, 0)
        }
        progressLayout.addView(progressBar, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        progressLayout.addView(progressText)
        sheetCard.addView(progressLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // 3. Danh sách Action Items
        // Action 1: Tải Sticker gốc (.webp / .gif)
        val downloadItem = createActionRow(
            badgeBgColor = "#2A1620",
            badgeTextColor = "#FE2C55",
            badgeGlyph = "↓",
            title = "Lưu vào máy",
            desc = "Tải file gốc (.webp / .gif) vào Thư viện ảnh"
        ) {
            dialog.dismiss()
            onDownloadOriginal()
        }
        sheetCard.addView(downloadItem)

        // Action 2: Gửi sang Telegram (GIF động)
        val telegramItem = createActionRow(
            badgeBgColor = "#142536",
            badgeTextColor = "#2AABEE",
            badgeGlyph = "➤",
            title = "Gửi sang Telegram (GIF động)",
            desc = "Chuyển thành GIF động lặp vô tận, không bị đóng băng"
        ) {
            progressLayout.visibility = View.VISIBLE
            downloadItem.visibility = View.GONE
            onShareTelegramGif { status ->
                activity.runOnUiThread {
                    progressText.text = status
                }
            }
        }
        sheetCard.addView(telegramItem)

        // Action 3: Sao chép URL
        val copyItem = createActionRow(
            badgeBgColor = "#132729",
            badgeTextColor = "#25F4EE",
            badgeGlyph = "🔗",
            title = "Sao chép liên kết",
            desc = "Lưu đường dẫn CDN gốc vào bộ nhớ tạm"
        ) {
            dialog.dismiss()
            onCopyUrl()
        }
        sheetCard.addView(copyItem)

        // 4. Đường kẻ phân cách & Nút Hủy
        val divider2 = View(activity).apply {
            setBackgroundColor(Color.parseColor("#222430"))
        }
        sheetCard.addView(divider2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
            topMargin = dp(6f)
            bottomMargin = dp(10f)
        })

        val cancelButton = TextView(activity).apply {
            text = "Hủy"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            background = createCardBackground()
            setOnClickListener { dialog.dismiss() }
        }
        val cancelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46f)
        )
        sheetCard.addView(cancelButton, cancelParams)

        dialog.setContentView(rootLayout)
        dialog.show()
    }

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }

    /**
     * Render drawable preview thành bitmap vừa khung để luôn hiển thị đầy đủ sticker
     */
    private fun createPreviewBitmap(drawable: Drawable?, sizePx: Int): Bitmap? {
        if (drawable == null) return null
        return runCatching {
            val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizePx
            val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizePx
            val scale = min(sizePx.toFloat() / iw, sizePx.toFloat() / ih).coerceAtMost(1f)
            val outW = (iw * scale).toInt().coerceAtLeast(1)
            val outH = (ih * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            drawable.setBounds(0, 0, outW, outH)
            drawable.draw(canvas)
            bmp
        }.getOrNull()
    }

    /**
     * Tạo 1 hàng item hành động theo chuẩn TikTok Tux Action Row
     */
    private fun createActionRow(
        badgeBgColor: String,
        badgeTextColor: String,
        badgeGlyph: String,
        title: String,
        desc: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardBackground()
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { onClick() }
        }

        // Badge icon tròn (36x36 dp)
        val iconBadge = TextView(activity).apply {
            text = badgeGlyph
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(badgeTextColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeBgColor))
                cornerRadius = dp(18f).toFloat()
            }
        }
        val badgeParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
            rightMargin = dp(12f)
        }
        row.addView(iconBadge, badgeParams)

        // Text Column
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleTv = TextView(activity).apply {
            this.text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val descTv = TextView(activity).apply {
            this.text = desc
            setTextColor(Color.parseColor("#8A8B91"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        textCol.addView(titleTv)
        textCol.addView(descTv)
        row.addView(textCol)

        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6f)
        }
        row.layoutParams = rowParams
        return row
    }

    /**
     * Background bo góc 10dp kèm hiệu ứng chạm (Touch / Ripple)
     */
    private fun createCardBackground(): Drawable {
        val normal = GradientDrawable().apply {
            setColor(Color.parseColor("#1E202B"))
            cornerRadius = dp(10f).toFloat()
        }
        val pressed = GradientDrawable().apply {
            setColor(Color.parseColor("#2B2E3D"))
            cornerRadius = dp(10f).toFloat()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val rippleColor = ColorStateList.valueOf(Color.parseColor("#383C50"))
            return RippleDrawable(rippleColor, normal, pressed)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }
}

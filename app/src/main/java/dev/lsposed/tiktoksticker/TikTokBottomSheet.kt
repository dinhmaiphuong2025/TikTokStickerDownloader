package dev.lsposed.tiktoksticker

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.PathShape
import android.os.Build
import android.util.PathParser
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

/**
 * TikTokBottomSheet - Giao diện Bottom Sheet mô phỏng 100% phong cách Tux Design System của TikTok.
 * Tông màu Dark Obsidian (#161823), bo góc chuẩn 16dp, drag handle pill, badge màu nhận diện.
 */
class TikTokBottomSheet(
    private val activity: Activity,
    private val previewBitmap: Bitmap?,
    private val onSaveOriginalWebp: () -> Unit,
    private val onSendToTelegramBot: (updateStatus: (String) -> Unit, onFinish: () -> Unit) -> Unit,
    private val onSaveWebm: (updateStatus: (String) -> Unit, onFinish: () -> Unit) -> Unit,
    private val onShareWebmToTelegram: (updateStatus: (String) -> Unit, onFinish: () -> Unit) -> Unit,
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
            if (previewBitmap != null) {
                setImageBitmap(previewBitmap)
            }
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

        // Khung trạng thái loading khi đang xử lý
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
        // Action 1: Lưu Sticker gốc
        val webpItem = createActionRow(
            badgeBgColor = "#2A1620",
            iconColor = "#FE2C55",
            svgPath = SVG_DOWNLOAD,
            title = "Lưu Sticker gốc",
            desc = "Lưu file ảnh gốc từ CDN vào máy (Pictures/TikTokStickers)"
        ) {
            dialog.dismiss()
            onSaveOriginalWebp()
        }
        sheetCard.addView(webpItem)

        // Action 2: Chia sẻ sticker gốc
        val shareItem = createActionRow(
            badgeBgColor = "#142536",
            iconColor = "#2AABEE",
            svgPath = SVG_SHARE,
            title = "Chia sẻ sticker gốc",
            desc = "Chia sẻ qua Telegram, Nekogram hoặc ứng dụng khác"
        ) {
            progressLayout.visibility = View.VISIBLE
            onSendToTelegramBot(
                { status -> activity.runOnUiThread { progressText.text = status } },
                { dialog.dismiss() }
            )
        }
        sheetCard.addView(shareItem)

        // Action 3: Lưu Video Sticker (.webm)
        val webmItem = createActionRow(
            badgeBgColor = "#1B2836",
            iconColor = "#3897F0",
            svgPath = SVG_PLAY,
            title = "Lưu Video Sticker (.webm)",
            desc = "Tự convert sang WebM VP9 (chuẩn 512px cho Telegram)"
        ) {
            progressLayout.visibility = View.VISIBLE
            onSaveWebm(
                { status -> activity.runOnUiThread { progressText.text = status } },
                { dialog.dismiss() }
            )
        }
        sheetCard.addView(webmItem)

        // Action 4: Gửi Video Sticker (.webm) vào Telegram (cho bot tạo sticker)
        val shareWebmItem = createActionRow(
            badgeBgColor = "#16283C",
            iconColor = "#00C7FF",
            svgPath = SVG_TELEGRAM,
            title = "Gửi WebM vào Telegram (Bot)",
            desc = "Mở thẳng Telegram gửi bot để nhận ngay sticker"
        ) {
            progressLayout.visibility = View.VISIBLE
            onShareWebmToTelegram(
                { status -> activity.runOnUiThread { progressText.text = status } },
                { dialog.dismiss() }
            )
        }
        sheetCard.addView(shareWebmItem)

        // Action 5: Sao chép URL
        val copyItem = createActionRow(
            badgeBgColor = "#20222C",
            iconColor = "#8A8B91",
            svgPath = SVG_LINK,
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
     * Tạo 1 hàng item hành động theo chuẩn TikTok Tux Action Row
     */
    private fun createActionRow(
        badgeBgColor: String,
        iconColor: String,
        svgPath: String,
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

        // Badge icon tròn (36x36 dp) chứa Vector Icon SVG sắc nét
        val iconBadgeContainer = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeBgColor))
                cornerRadius = dp(18f).toFloat()
            }
        }
        val iconView = ImageView(activity).apply {
            setImageDrawable(createSvgDrawable(svgPath, Color.parseColor(iconColor), dp(18f)))
        }
        val iconCenterParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        iconBadgeContainer.addView(iconView, iconCenterParams)

        val badgeParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
            rightMargin = dp(12f)
        }
        row.addView(iconBadgeContainer, badgeParams)

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

    /**
     * Tạo ShapeDrawable từ chuỗi toạ độ SVG Path (24x24 viewport)
     */
    private fun createSvgDrawable(pathData: String, color: Int, sizePx: Int): Drawable {
        return runCatching {
            val path = PathParser.createPathFromPathData(pathData)
            ShapeDrawable(PathShape(path, 24f, 24f)).apply {
                intrinsicWidth = sizePx
                intrinsicHeight = sizePx
                paint.color = color
                paint.isAntiAlias = true
            }
        }.getOrElse {
            ColorDrawable(Color.TRANSPARENT)
        }
    }

    companion object {
        // Material Design Vector PathData (Chuẩn Viewport 24x24)
        private const val SVG_DOWNLOAD = "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z"
        private const val SVG_SHARE = "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92c0-1.61-1.31-2.92-2.92-2.92z"
        private const val SVG_PLAY = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"
        private const val SVG_TELEGRAM = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 0 0-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.95-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.75-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.36.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .37z"
        private const val SVG_LINK = "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
    }
}

package com.slava.pdfwatcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.LinearLayout

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.ripple(content: Drawable?, mask: Drawable? = content): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), content, mask)

fun Context.iconButton(icon: Int, desc: String, tint: Int = getColor(R.color.text), onClick: () -> Unit) =
    ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = desc
        tooltipText = desc
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), null, null)
        layoutParams = LinearLayout.LayoutParams(dpi(48), dpi(48))
        setOnClickListener { onClick() }
    }

fun View.clipRounded(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}

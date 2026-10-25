package com.mhttn.sunplusremote

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#111318")
    val PANEL = Color.parseColor("#1A1D24")
    val BTN = Color.parseColor("#2A2F3A")
    val BTN_DARK = Color.parseColor("#20242D")
    val ACCENT = Color.parseColor("#2F6BFF")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#30A46C")
    val YELLOW = Color.parseColor("#F5C400")
    val BLUE = Color.parseColor("#3E63DD")
    val TEXT = Color.parseColor("#F2F4F8")
    val SUBTEXT = Color.parseColor("#9AA3B2")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(v: Int): Int = (v * App.ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    fun button(ctx: Context, text: String, bg: Int = BTN, sizeSp: Float = 15f, fg: Int = TEXT, bold: Boolean = false): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = sizeSp
        b.background = round(bg, 12)
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(4), 0, dp(4), 0)
        b.gravity = Gravity.CENTER
        if (bold) b.setTypeface(b.typeface, Typeface.BOLD)
        return b
    }

    fun label(ctx: Context, text: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    /** Horizontal row of equally weighted views, each `heightDp` high. */
    fun row(ctx: Context, vararg views: View, heightDp: Int = 52, gapDp: Int = 6, weights: FloatArray? = null): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for (i in views.indices) {
            val w = if (weights != null && i < weights.size) weights[i] else 1f
            val lp = LinearLayout.LayoutParams(0, dp(heightDp), w)
            if (i > 0) lp.leftMargin = dp(gapDp)
            r.addView(views[i], lp)
        }
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(gapDp)
        r.layoutParams = rlp
        return r
    }

    /** Vertical stack where each view is `heightDp` high and fills the width. */
    fun column(ctx: Context, vararg views: View, heightDp: Int = 52, gapDp: Int = 6): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        for (i in views.indices) {
            val lp = LinearLayout.LayoutParams(MATCH, dp(heightDp))
            if (i > 0) lp.topMargin = dp(gapDp)
            c.addView(views[i], lp)
        }
        return c
    }

    fun spacer(ctx: Context): View = View(ctx)

    fun section(ctx: Context, text: String): TextView {
        val t = label(ctx, text, 12f, SUBTEXT, true)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        t.layoutParams = lp
        return t
    }
}

/**
 * Fires on release (so scrolling the page never presses a key by accident).
 * With repeat = true, holding the button keeps firing, like holding a real remote key.
 */
fun View.onTap(repeat: Boolean = false, action: () -> Unit) {
    var started = false
    var cancelled = false
    var downX = 0f
    var downY = 0f
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    val runner = object : Runnable {
        override fun run() {
            started = true
            action()
            App.main.postDelayed(this, 150)
        }
    }
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                started = false
                cancelled = false
                downX = ev.x
                downY = ev.y
                v.alpha = 0.55f
                if (repeat) App.main.postDelayed(runner, 380)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!cancelled && (Math.abs(ev.x - downX) > slop * 3 || Math.abs(ev.y - downY) > slop * 3)) {
                    cancelled = true
                    App.main.removeCallbacks(runner)
                    v.alpha = 1f
                }
            }
            MotionEvent.ACTION_UP -> {
                App.main.removeCallbacks(runner)
                v.alpha = 1f
                if (!started && !cancelled) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    action()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                App.main.removeCallbacks(runner)
                v.alpha = 1f
            }
        }
        true
    }
}

/** Two-line list row used by several pages. */
class RowView(ctx: Context) : LinearLayout(ctx) {
    val title = TextView(ctx)
    val sub = TextView(ctx)

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10))
        title.textSize = 16f
        title.setTextColor(Ui.TEXT)
        sub.textSize = 12f
        sub.setTextColor(Ui.SUBTEXT)
        addView(title, LayoutParams(Ui.MATCH, Ui.WRAP))
        addView(sub, LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    fun set(t: String, s: String, selected: Boolean = false) {
        title.text = t
        sub.text = s
        sub.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        setBackgroundColor(if (selected) Color.parseColor("#1B2A4A") else Color.TRANSPARENT)
    }
}

class ListAdapter<T>(var items: List<T>, private val bindRow: (T, Int, View?) -> View) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any? = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
        bindRow(items[position], position, convertView)

    fun set(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }
}

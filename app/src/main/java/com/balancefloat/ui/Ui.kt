package com.balancefloat.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.ColorUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.balancefloat.R
import com.balancefloat.data.BalanceRepo
import com.balancefloat.data.Provider
import java.util.WeakHashMap

object Ui {
    private data class BaseDims(
        val textSize: Float,
        val padL: Int, val padT: Int, val padR: Int, val padB: Int,
        val width: Int, val height: Int,
        val marginL: Int, val marginT: Int, val marginR: Int, val marginB: Int,
        val minWidth: Int
    )

    private val baseDims = WeakHashMap<View, BaseDims>()

    /** 悬浮窗背景预设；全部是深色，保证浅色文字可读。0 表示用内置 drawable。 */
    val BG_PRESETS: List<Pair<String, Int>> = listOf(
        "默认" to 0,
        "墨黑" to 0xF2101218.toInt(),
        "午夜蓝" to 0xF2161B24.toInt(),
        "石墨" to 0xF23A3F49.toInt(),
        "藏青" to 0xF2142338.toInt(),
        "深紫" to 0xF21F1830.toInt()
    )

    /**
     * 圆角纯色背景，替代 bg_pill / bg_detail。
     * 保留 1dp 的 15% 白描边，和内置样式一致。
     */
    fun roundedBg(ctx: Context, color: Int, radiusDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * d
            setStroke((1 * d).toInt().coerceAtLeast(1), 0x26FFFFFF)
        }
    }

    /** 一排色块；选中项加一圈 accent 描边。点击回调传入 ARGB（0 = 默认）。 */
    fun fillSwatches(ctx: Context, row: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val d = ctx.resources.displayMetrics.density
        BG_PRESETS.forEach { (_, color) ->
            val on = color == selected
            val size = ((if (on) 26 else 21) * d).toInt()
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (color == 0) 0xFF161B24.toInt() else color)
                    setStroke(
                        ((if (on) 2 else 1) * d).toInt(),
                        if (on) ctx.getColor(R.color.accent) else 0x40FFFFFF
                    )
                }
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (9 * d).toInt() }
                setOnClickListener { onPick(color) }
            })
        }
    }

    /** 状态灯：layer-list 逐层染色，保留外层光晕的 alpha 渐变。 */
    fun tintDot(v: View, color: Int) {
        val bg = v.background?.mutate()
        if (bg is LayerDrawable) {
            val n = bg.numberOfLayers
            for (i in 0 until n) {
                // 最后一层是核心，往白偏 25%，看起来更像点亮
                val c = if (i == n - 1) ColorUtils.blendARGB(color, Color.WHITE, 0.25f) else color
                bg.getDrawable(i)?.mutate()?.setTint(c)
            }
            v.background = bg
            return
        }
        if (bg != null) {
            bg.setTint(color)
            v.background = bg
            return
        }
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /**
     * 按比例缩放整棵视图树：字号、padding、固定宽高、margin、minWidth。
     * 首次调用时记录原始值，后续反复调用不会累积放大。
     */
    fun applyScale(root: View, scale: Float) {
        val s = scale.coerceIn(0.7f, 1.6f)

        fun walk(v: View) {
            val lp = v.layoutParams
            val base = baseDims.getOrPut(v) {
                val m = lp as? ViewGroup.MarginLayoutParams
                BaseDims(
                    textSize = (v as? TextView)?.textSize ?: 0f,
                    padL = v.paddingLeft,
                    padT = v.paddingTop,
                    padR = v.paddingRight,
                    padB = v.paddingBottom,
                    width = lp?.width ?: 0,
                    height = lp?.height ?: 0,
                    marginL = m?.leftMargin ?: 0,
                    marginT = m?.topMargin ?: 0,
                    marginR = m?.rightMargin ?: 0,
                    marginB = m?.bottomMargin ?: 0,
                    minWidth = v.minimumWidth
                )
            }

            (v as? TextView)?.let {
                if (base.textSize > 0f) {
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.textSize * s)
                }
            }
            v.setPadding(
                (base.padL * s).toInt(),
                (base.padT * s).toInt(),
                (base.padR * s).toInt(),
                (base.padB * s).toInt()
            )
            if (base.minWidth > 0) v.minimumWidth = (base.minWidth * s).toInt()

            if (lp != null) {
                if (base.width > 0) lp.width = (base.width * s).toInt()
                if (base.height > 0) lp.height = (base.height * s).toInt()
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = (base.marginL * s).toInt()
                    lp.topMargin = (base.marginT * s).toInt()
                    lp.rightMargin = (base.marginR * s).toInt()
                    lp.bottomMargin = (base.marginB * s).toInt()
                }
                v.layoutParams = lp
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }

        walk(root)
    }

    /**
     * 指示灯颜色。
     * 订阅额度按固定档位（<70 绿 / 70–89 黄 / ≥90 红）；
     * 余额低于预警值红、低于 3×预警值黄，其余绿；查询失败红。
     */
    fun dotColor(ctx: Context, p: Provider, r: BalanceRepo.Result?): Int = when {
        !p.enabled || r == null -> ctx.getColor(R.color.text_muted)
        !r.ok -> ctx.getColor(R.color.red)
        p.isQuota() -> r.windows.maxOfOrNull { it.usedPercent }
            ?.let { Fmt.quotaColor(ctx, it) } ?: ctx.getColor(R.color.text_muted)
        BalanceRepo.warnLevel(p, r) == 2 -> ctx.getColor(R.color.red)
        BalanceRepo.warnLevel(p, r) == 1 -> ctx.getColor(R.color.amber)
        else -> ctx.getColor(R.color.green)
    }
}

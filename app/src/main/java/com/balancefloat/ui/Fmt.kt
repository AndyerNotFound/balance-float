package com.balancefloat.ui

import com.balancefloat.data.Money
import com.balancefloat.data.QuotaWindow
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {

    fun symbol(unit: String): String = when (unit.uppercase()) {
        "CNY", "RMB" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        else -> if (unit.isBlank()) "" else unit.uppercase() + " "
    }

    fun money(v: Double?, unit: String): String {
        if (v == null) return "—"
        val n = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(v)
        return symbol(unit) + n
    }

    fun plain(v: Double?): String =
        if (v == null) "—" else DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(v)

    /** "¥12.34  $5.00" */
    fun moneyList(list: List<Money>): String =
        if (list.isEmpty()) "—"
        else list.joinToString("  ") { money(it.value, it.unit) }

    /** 明细里竖排显示多币种 */
    fun moneyLines(list: List<Money>): String =
        if (list.isEmpty()) "—"
        else list.joinToString("\n") { money(it.value, it.unit) }

    fun percent(v: Double?): String {
        if (v == null) return "—"
        return if (abs(v - v.roundToInt()) < 0.05) "${v.roundToInt()}%"
        else String.format(Locale.US, "%.1f%%", v)
    }

    fun clock(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun ago(ts: Long): String {
        if (ts <= 0) return "尚未查询"
        val sec = (System.currentTimeMillis() - ts) / 1000
        return when {
            sec < 60 -> "刚刚更新"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86400 -> "${sec / 3600} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }

    /** 距重置还有多久 */
    fun resetIn(ts: Long?): String {
        if (ts == null) return ""
        val ms = ts - System.currentTimeMillis()
        if (ms <= 0) return "已重置"
        val min = ms / 60000
        return when {
            min < 60 -> "${min.coerceAtLeast(1)}m 后重置"
            min < 1440 -> "${min / 60}h${min % 60}m 后重置"
            else -> "${min / 1440}d 后重置"
        }
    }

    /** "5h 42% · 周 18%" */
    fun quotaLine(windows: List<QuotaWindow>): String {
        if (windows.isEmpty()) return "—"
        return windows.joinToString(" · ") { "${it.label} ${percent(it.usedPercent)}" }
    }

    fun pillIntervalLabel(sec: Int): String = if (sec < 60) "$sec 秒" else "${sec / 60} 分钟"

    fun intervalLabel(sec: Int): String = when (sec) {
        0 -> "手动"
        60 -> "1 分钟"
        300 -> "5 分钟"
        900 -> "15 分钟"
        1800 -> "30 分钟"
        else -> if (sec % 60 == 0) "${sec / 60} 分钟" else "$sec 秒"
    }

    /** 按占用率给颜色：<70 绿，70-89 黄，>=90 红 */
    fun quotaColor(activity: android.content.Context, pct: Double): Int = when {
        pct >= 90 -> activity.getColor(com.balancefloat.R.color.red)
        pct >= 70 -> activity.getColor(com.balancefloat.R.color.amber)
        else -> activity.getColor(com.balancefloat.R.color.green)
    }
}

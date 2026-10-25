package com.balancefloat.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.balancefloat.R
import com.balancefloat.data.BalanceRepo
import com.balancefloat.data.Kind
import com.balancefloat.data.Mode
import com.balancefloat.data.Money
import com.balancefloat.data.Provider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

/** 渠道编辑弹窗：余额渠道 + 订阅额度渠道（Codex / Claude / OpenCode Go / 自定义）。 */
object EditorDialog {

    private class Field(
        val root: TextInputLayout,
        val til: TextInputLayout,
        val input: TextInputEditText
    )

    fun show(
        activity: Activity,
        existing: Provider?,
        onSave: (Provider) -> Unit,
        onDelete: (Provider) -> Unit
    ) {
        val ctx = activity
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        var kind = existing?.kind ?: Kind.DEEPSEEK
        var currentPreset = Provider.preset(kind)
        var unit = existing?.unit ?: currentPreset.unit
        var auth = existing?.auth ?: currentPreset.auth

        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(16))
        }
        scroll.addView(box)

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(16), 0, dp(4))
        }

        fun fieldInto(
            parent: LinearLayout,
            title: String,
            hint: String,
            value: String,
            password: Boolean = false
        ): Field {
            val til = TextInputLayout(
                ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle
            ).apply {
                this.hint = title
                setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
                if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val et = TextInputEditText(til.context).apply {
                this.hint = hint
                setText(value)
                textSize = 14f
                setSingleLine(!password)
            }
            til.addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            parent.addView(
                til,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
            return Field(til, til, et)
        }

        fun pill(text: String) = TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.getColor(R.color.accent))
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ctx.getDrawable(R.drawable.bg_chip)
            isClickable = true
        }

        fun unitText(u: String) = when (u.uppercase()) {
            "CNY" -> "CNY  ¥"
            "USD" -> "USD  $"
            else -> "跟随返回"
        }

        fun authText(a: String) = when (a) {
            "x-api-key" -> "x-api-key"
            "cookie" -> "Cookie"
            "none" -> "不认证"
            else -> "Bearer"
        }

        fun modeText(m: Mode) = if (m == Mode.QUOTA) "订阅额度" else "账户余额"

        // ---- 类型 ----
        box.addView(label("类型"))
        val rowType = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvType = TextView(ctx).apply {
            text = kind.label + "  ·  " + modeText(kind.mode)
            setTextColor(ctx.getColor(R.color.text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnType = pill("更换")
        rowType.addView(tvType)
        rowType.addView(btnType)
        box.addView(rowType)

        // ---- 名称 / 凭据 ----
        val nameField = fieldInto(box, "名称", "例如：Codex 主号", existing?.name ?: currentPreset.name)
        val keyField = fieldInto(box, "API Key", "sk-…", existing?.apiKey ?: "", password = true)
        val accountField = fieldInto(
            box, "ChatGPT Account ID（可选）", "tokens.account_id", existing?.accountId ?: ""
        )
        val orgField = fieldInto(
            box, "组织 ID（可选，留空自动发现）", "organization uuid", existing?.orgId ?: ""
        )
        val workspaceField = fieldInto(box, "Workspace ID", "wrk_…", existing?.workspaceId ?: "")
        val warnInit = existing?.warnBelow?.takeIf { it > 0 }?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
        } ?: ""
        val warnField = fieldInto(
            box,
            "余额预警线（可选）",
            "低于它红色；低于 3×它黄色；0 或留空关闭",
            warnInit
        )
        warnField.input.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        // ---- 高级 ----
        val tvAdvanced = TextView(ctx).apply {
            text = "高级选项"
            setTextColor(ctx.getColor(R.color.text_sub))
            textSize = 12f
            setPadding(0, dp(20), 0, dp(4))
            isClickable = true
        }
        box.addView(tvAdvanced)

        val advanced = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (kind == Kind.CUSTOM || kind == Kind.CUSTOM_QUOTA) View.VISIBLE else View.GONE
        }
        box.addView(advanced)

        val urlField = fieldInto(
            advanced, "接口地址", "https://…",
            existing?.endpoint ?: currentPreset.endpoint
        )
        urlField.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        val pathField = fieldInto(
            advanced, "余额 JSON 路径", "data.balance",
            existing?.jsonPath ?: currentPreset.jsonPath
        )
        val subField = fieldInto(
            advanced, "扣减 JSON 路径（可选）", "如 data.total_usage",
            existing?.subtractPath ?: currentPreset.subtractPath
        )

        val rowUnit = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rowUnit.addView(label("币种"))
        val tvUnit = pill(unitText(unit))
        tvUnit.setOnClickListener {
            unit = when (unit.uppercase()) {
                "CNY" -> "USD"
                "USD" -> ""
                else -> "CNY"
            }
            tvUnit.text = unitText(unit)
        }
        rowUnit.addView(tvUnit)
        advanced.addView(rowUnit)

        val windowsField = fieldInto(
            advanced,
            "额度窗口（每行：标签|百分比路径|重置路径）",
            "5h|data.five_hour.utilization|data.five_hour.resets_at",
            existing?.windowsSpec ?: currentPreset.windowsSpec
        )
        windowsField.input.setSingleLine(false)
        windowsField.input.minLines = 3
        windowsField.input.gravity = Gravity.TOP or Gravity.START
        windowsField.input.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        val rowAuth = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rowAuth.addView(label("认证方式"))
        val tvAuth = pill(authText(auth))
        tvAuth.setOnClickListener {
            auth = when (auth) {
                "bearer" -> "x-api-key"
                "x-api-key" -> "cookie"
                "cookie" -> "none"
                else -> "bearer"
            }
            tvAuth.text = authText(auth)
        }
        rowAuth.addView(tvAuth)
        advanced.addView(rowAuth)

        // ---- 启用 ----
        val cbEnabled = MaterialCheckBox(ctx).apply {
            text = "启用该渠道"
            setTextColor(ctx.getColor(R.color.text))
            textSize = 14f
            isChecked = existing?.enabled ?: true
            setPadding(0, dp(14), 0, 0)
        }
        box.addView(cbEnabled)

        val tvStatus = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(tvStatus)

        fun status(msg: String, colorRes: Int) {
            tvStatus.text = msg
            tvStatus.setTextColor(ctx.getColor(colorRes))
        }

        fun keyLabelFor(k: Kind): String = when (k) {
            Kind.CODEX_QUOTA -> "OAuth Access Token"
            Kind.GEMINI_QUOTA -> "Google Access Token"
            Kind.CLAUDE_QUOTA -> "sessionKey"
            Kind.OPENCODE_GO -> "auth cookie"
            Kind.CUSTOM_QUOTA -> "Token（接口免认证可留空）"
            else -> "API Key"
        }

        fun cookieNameFor(k: Kind): String = when (k) {
            Kind.CLAUDE_QUOTA -> "sessionKey"
            Kind.OPENCODE_GO -> "auth"
            else -> ""
        }

        fun applyKind(k: Kind, fillPreset: Boolean) {
            kind = k
            val quota = k.mode == Mode.QUOTA
            keyField.til.hint = keyLabelFor(k)
            keyField.input.hint = when (k) {
                Kind.CODEX_QUOTA -> "tokens.access_token"
                Kind.GEMINI_QUOTA -> "设置页登录后自动填"
                Kind.CLAUDE_QUOTA -> "sessionKey=… 的值"
                Kind.OPENCODE_GO -> "Fe26.2**…"
                else -> "sk-…"
            }
            accountField.root.visibility = if (k == Kind.CODEX_QUOTA) View.VISIBLE else View.GONE
            orgField.root.visibility = if (k == Kind.CLAUDE_QUOTA) View.VISIBLE else View.GONE
            workspaceField.root.visibility = if (k == Kind.OPENCODE_GO) View.VISIBLE else View.GONE
            warnField.root.visibility = if (!quota) View.VISIBLE else View.GONE

            pathField.root.visibility = if (!quota) View.VISIBLE else View.GONE
            subField.root.visibility = if (!quota) View.VISIBLE else View.GONE
            rowUnit.visibility = if (!quota) View.VISIBLE else View.GONE

            windowsField.root.visibility = if (k == Kind.CUSTOM_QUOTA) View.VISIBLE else View.GONE

            tvType.text = k.label + "  ·  " + modeText(k.mode)
            if (k == Kind.CUSTOM_QUOTA || k == Kind.CUSTOM || k == Kind.OPENCODE_GO) {
                advanced.visibility = View.VISIBLE
            }

            if (fillPreset) {
                val p = Provider.preset(k)
                urlField.input.setText(p.endpoint)
                pathField.input.setText(p.jsonPath)
                subField.input.setText(p.subtractPath)
                windowsField.input.setText(p.windowsSpec)
                unit = p.unit
                tvUnit.text = unitText(unit)
                auth = p.auth
                tvAuth.text = authText(auth)
                accountField.input.setText("")
                orgField.input.setText("")
                workspaceField.input.setText("")
            }
        }

        fun build(): Provider? {
            val name = nameField.input.text.toString().trim().ifBlank { kind.label }
            val key = keyField.input.text.toString().trim()
            val url = urlField.input.text.toString().trim()
            val path = pathField.input.text.toString().trim()
            val quota = kind.mode == Mode.QUOTA
            val spec = windowsField.input.text.toString().trim()
            val warnText = warnField.input.text.toString().trim()
            val warn = warnText.toDoubleOrNull()

            if (key.isEmpty() && auth != "none" && kind != Kind.CUSTOM_QUOTA) {
                status("填上 " + keyLabelFor(kind), R.color.red)
                return null
            }
            if (warnText.isNotEmpty() && (warn == null || warn < 0)) {
                status("余额预警线要填一个非负数字，或留空关闭", R.color.red)
                return null
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                status("接口地址要以 http:// 或 https:// 开头", R.color.red)
                return null
            }
            if (!quota && path.isEmpty()) {
                status("填上余额 JSON 路径，例如 data.balance", R.color.red)
                return null
            }
            if (kind == Kind.CUSTOM_QUOTA) {
                val hasLine = spec.lineSequence().any { line ->
                    val parts = line.split('|')
                    parts.size >= 2 && parts[1].isNotBlank()
                }
                if (!hasLine) {
                    status("至少填一行：标签|百分比路径|重置路径", R.color.red)
                    return null
                }
            }
            if (kind == Kind.OPENCODE_GO && workspaceField.input.text.isNullOrBlank()) {
                status("OpenCode Go 需要 Workspace ID（wrk_…）", R.color.red)
                return null
            }
            if (url.startsWith("http://")) {
                val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
                if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2") {
                    status("注意：明文 HTTP 会暴露凭据", R.color.amber)
                }
            }
            return Provider(
                id = existing?.id ?: UUID.randomUUID().toString(),
                kind = kind,
                name = name,
                apiKey = key,
                refreshToken = existing?.refreshToken ?: "",
                tokenExpiresAt = existing?.tokenExpiresAt ?: 0L,
                endpoint = url,
                baseUrl = "",
                jsonPath = if (quota) "" else path,
                subtractPath = if (quota) "" else subField.input.text.toString().trim(),
                unit = if (quota) "" else unit,
                auth = auth,
                enabled = cbEnabled.isChecked,
                warnBelow = if (quota) 0.0 else (warn ?: 0.0),
                accountId = accountField.input.text.toString().trim(),
                orgId = orgField.input.text.toString().trim(),
                workspaceId = workspaceField.input.text.toString().trim(),
                cookieName = cookieNameFor(kind),
                windowsSpec = if (kind == Kind.CUSTOM_QUOTA) spec else ""
            )
        }

        // ---- 类型选择 ----
        btnType.setOnClickListener {
            val kinds = Kind.values()
            val labels = kinds.map {
                val suffix = if (it.mode == Mode.QUOTA) "  ·  订阅额度" else "  ·  余额"
                it.label + suffix
            }.toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("渠道类型")
                .setItems(labels) { d, i ->
                    val k = kinds[i]
                    if (!k.supported) {
                        status("该类型暂不支持", R.color.amber)
                        d.dismiss()
                        return@setItems
                    }
                    val oldDefault = currentPreset.name
                    currentPreset = Provider.preset(k)
                    applyKind(k, true)
                    val typed = nameField.input.text.toString().trim()
                    if (typed.isEmpty() || typed == oldDefault) {
                        nameField.input.setText(currentPreset.name)
                    }
                    status("已套用 " + k.label + " 预设", R.color.text_muted)
                    d.dismiss()
                }
                .show()
        }

        tvAdvanced.setOnClickListener {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ---- 操作按钮 ----
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
        }

        val btnDelete = TextView(ctx).apply {
            text = "删除"
            setTextColor(ctx.getColor(R.color.red))
            textSize = 13f
            setPadding(dp(4), dp(10), dp(12), dp(10))
            isClickable = true
            visibility = if (existing != null) View.VISIBLE else View.GONE
        }
        actions.addView(btnDelete, LinearLayout.LayoutParams(0, -2, 1f))

        val btnTest = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "测试"
            textSize = 13f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            cornerRadius = dp(16)
        }
        actions.addView(btnTest)

        val btnSave = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "保存"
            textSize = 13f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            cornerRadius = dp(16)
        }
        actions.addView(btnSave, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })

        box.addView(actions)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "添加渠道" else "编辑渠道")
            .setView(scroll)
            .create()

        btnTest.setOnClickListener {
            val p = build() ?: return@setOnClickListener
            status("测试中…", R.color.text_sub)
            BalanceRepo.test(p) { r ->
                if (!r.ok) {
                    status("失败：" + r.error, R.color.red)
                } else if (p.isQuota()) {
                    status(
                        "正常：" + Fmt.quotaLine(r.windows) +
                            if (r.note.isNotBlank()) " · " + r.note else "",
                        R.color.green
                    )
                } else {
                    val amounts = r.amounts.ifEmpty {
                        r.amount?.let { listOf(Money(it, r.unit.ifBlank { p.unit })) } ?: emptyList()
                    }
                    status("正常：" + Fmt.moneyList(amounts), R.color.green)
                }
            }
        }

        btnSave.setOnClickListener {
            val p = build() ?: return@setOnClickListener
            onSave(p)
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            val p = existing ?: return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle("删除渠道")
                .setMessage("删除「" + p.name.ifBlank { p.kind.label } + "」？")
                .setPositiveButton("删除") { _, _ ->
                    onDelete(p)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        applyKind(kind, false)

        dialog.show()
        val dm = ctx.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

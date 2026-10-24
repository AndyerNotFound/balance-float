# 余额浮窗 · BalanceFloat

Android 悬浮窗，把 API 账户余额和订阅额度钉在屏幕上。Material You（动态取色）+ Android 12 模糊效果。

- 包名：`com.balancefloat`
- 版本：0.6.0（versionCode 7）
- 最低系统：Android 8.0（API 26），targetSdk 36

## 功能

| 模块 | 说明 |
|---|---|
| Material You | Material 3 组件 + `DynamicColors` 跟随壁纸取色（Android 12+），低版本用暗色兜底 |
| 悬浮胶囊 | 只轮播每个渠道自己的余额/额度，不做跨渠道合计（各家计价与币种不同）；间隔 1/2/3/4/5/8/10 秒可设，可关自动轮播改为手动切 |
| 多币种 | DeepSeek 同时显示 CNY 与 USD，例如 `¥47.86 / $0.00`；主界面、胶囊、明细都并列显示 |
| 展开动画 | 双击胶囊时窗口尺寸 + 透明度一起过渡（230ms，M3 缓动），不是生硬切换 |
| 悬浮窗设置 | 明细卡片右上角 ⚙：胶囊/详情缩放、固定宽高、配色、自动轮播、锁定位置、常驻展开，松手生效并持久化 |
| 尺寸配色 | 胶囊宽 0–320dp、高 0–120dp、详情宽 0–380dp（0 = 自适应）；6 套深色配色，App 设置里也能改 |
| 明细卡片 | 每个额度窗口一行：标签、百分比、重置倒计时、独立进度条 |
| 余额预警 | 每个余额渠道可设预警线：低于它红色，低于 3×它黄色，其余绿色；0 或留空关闭 |
| 应用内额度 | 主界面每个渠道显示 5h / 7d / 1M 三层进度条；额度固定按 <70 绿 / 70–89 黄 / ≥90 红 变色 |
| 背景 | 全屏渐变 + 顶部/底部模糊光斑；状态栏用同色遮罩，不再只糊顶部一块 |
| 模糊 | 悬浮窗可选窗口级背景模糊（Android 12+，实验性）；ColorOS 会连整屏一起糊，默认关闭 |
| 手势 | 拖动移位（可锁定）· 单击切下一条 · 双击展开/收起 · 长按刷新 · 详情卡里的 `App` 按钮打开应用 |
| 定时刷新 | 1 / 5 / 15 / 30 分钟，或手动 |
| 存储 | 全部在应用私有目录 `/data/user/0/com.balancefloat/shared_prefs/balance_float.xml`；Key / Token 用 Android Keystore AES-GCM 加密 |

## 余额接口

| 类型 | 接口 | JSON 路径 | 币种 |
|---|---|---|---|
| DeepSeek | `GET https://api.deepseek.com/user/balance` | `balance_infos` 数组按币种选择（优先 CNY，取有余额的条目） | CNY / USD |
| 硅基流动 | `GET https://api.siliconflow.cn/v1/user/info` | `data.balance` | CNY |
| 硅基流动（国际） | `GET https://api.siliconflow.com/v1/user/info` | `data.balance` | USD |
| 智谱 BigModel | `GET https://open.bigmodel.cn/api/paas/v4/user/info` | `data.balance` | CNY |
| OpenRouter | `GET https://openrouter.ai/api/v1/credits` | `data.total_credits` − `data.total_usage` | USD |
| 本机 ai-gateway | `GET http://127.0.0.1:16384/v1/balance` | `total_remain` | 跟随返回 |
| 自定义中转站 | 任意 URL | 任意点路径（支持 `a.0.b` 数组下标） | 可选 |

## 订阅额度

| 类型 | 接口 | 凭据 | 返回窗口 |
|---|---|---|---|
| Codex / ChatGPT | `GET https://chatgpt.com/backend-api/wham/usage` | `tokens.access_token` + `tokens.account_id`（`~/.codex/auth.json`） | `5h` / `7d` / `1M`（接口返回几层显示几层） |
| Claude 订阅 | `GET https://claude.ai/api/organizations/{org}/usage` | 浏览器 Cookie 里的 `sessionKey`（约 30 天） | `5h` / `7d` / `1M` + Sonnet/Opus |
| OpenCode Go | `GET https://opencode.ai/workspace/{ws}/go` | `workspaceId`（`wrk_…`）+ `auth` cookie（`Fe26.2**…`） | `5h` / `7d` / `1M` |
| 自定义额度 | 任意 URL | Bearer / x-api-key / Cookie / 无 | 任意多个窗口 |

**取凭据**

- Codex：Termux 里 `cat ~/.codex/auth.json` → `tokens.access_token` 填 Token，`tokens.account_id` 填 Account ID。token 会过期，401 时重取。
- Claude：电脑浏览器登录 claude.ai → DevTools → Application → Cookies → `claude.ai` → 复制 `sessionKey`。组织 ID 留空会自动发现。
- OpenCode Go：`https://opencode.ai/workspace/<wrk_id>/go` 里的 workspace id；DevTools → Cookies → `opencode.ai` → 复制 `auth`（`Fe26.2**` 开头）。官方还没开放 `/zen/go/v1/usage`（PR #16513），所以这里解析网页里的 `rollingUsage/weeklyUsage/monthlyUsage`。

> 这些都是**未公开接口 / 网页结构**，随时可能变；只读，不会消耗额度。OpenCode 抓的是你自己的登录态页面。

## 自定义额度

一行一个窗口：

```
5h|data.five_hour.utilization|data.five_hour.resets_at
7d|data.seven_day.utilization|data.seven_day.resets_at
1M|data.monthly.utilization|data.monthly.resets_at
```

- 标签 | **0–100 的百分比路径** | 重置时间路径（ISO-8601 或 Unix 秒，可留空）
- 想显示几层写几行

## 权限与系统

| 权限 | 用途 |
|---|---|
| `INTERNET` | 查询余额/额度 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 常驻刷新 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |

国产 ROM 可能还需要「后台弹出界面 / 自启动 / 省电白名单」。

## 安全

- 凭据只存在应用私有目录 `/data/user/0/com.balancefloat/shared_prefs/balance_float.xml`，不在 Termux，也不在 `/workspace`。
- 其中 `key` 字段是 `enc1:iv:密文`，AES-256-GCM，密钥由 Android Keystore 保管（alias `balance_float_v1`，不可导出）；`accountId` / `orgId` / `workspaceId` / `endpoint` 等非密钥字段是明文 JSON。
- 不上传任何数据；查询只发往用户填写的地址。
- 关闭 `allowBackup`；卸载或清除数据会连 Keystore 密钥一起销毁，旧密文无法再解密。
- **sessionKey / auth cookie / OAuth token 等同于账号登录态**，不要截图或外发；怀疑泄露就去对应网站退出所有会话。
- APK 用 debug keystore 签名，仅适合侧载。

## 构建

```bash
# Termux（JDK 21 + Android SDK 36 + Gradle 9.5.0）
cd ~/balance-float
~/.gradle/wrapper/dists/gradle-9.5.0-bin/*/gradle-9.5.0/bin/gradle assembleRelease
```

依赖：`androidx.appcompat:1.7.1`、`com.google.android.material:1.12.0`（Google Maven）。
`gradle.properties` 的 `android.aapt2FromMavenOverride` 指向 Termux 的 aapt2。

## 已知限制

- OpenCode Go 靠解析网页，页面结构变了就会报「没找到额度数据」。
- Codex token 过期需手动更新；不做自动刷新，避免和 Codex CLI 抢 refresh_token。
- Claude 的 `1M` 只有接口返回 monthly 字段时才显示；OpenAI 目前通常只有 5h + 周。
- ColorOS 上窗口级 `blurBehindRadius` 实测会把整屏内容一起糊掉（连状态栏和 App 都糊），所以悬浮窗模糊默认关闭；设置里可手动开，胶囊本身用半透明玻璃底。
- DeepSeek 的 `balance_infos` 可能含多个币种且顺序不固定；现在会把所有币种并列显示（如 `¥47.86 / $0.00`），主币种用于预警颜色；`is_available=false` 会标注「账户不可用」。
- 主界面不再显示跨渠道合计/全局最高占用，避免把不同计价方式的数字加在一起。
- 未公开接口随时可能变；重启手机不会自动挂起（打开一次 App 恢复）。

## 目录

```
app/src/main/java/com/balancefloat/
├── App.kt                   DynamicColors 初始化
├── MainActivity.kt          主界面（M3 + 三层进度条）
├── data/
│   ├── Model.kt             渠道模型、余额/额度预设
│   ├── Store.kt             持久化（含轮播间隔）
│   ├── Crypto.kt            Keystore AES-GCM
│   └── BalanceRepo.kt       并发查询、余额/额度/网页解析
└── ui/
    ├── OverlayService.kt    悬浮窗 + 前台服务 + 轮播 + 展开动画
    ├── EditorDialog.kt      M3 编辑弹窗（OutlinedBox）
    ├── Fmt.kt               金额/百分比/时间格式化
    └── Ui.kt                小工具
```

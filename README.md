# Monitor for Android 16

`Monitor` 是一个完全在 Android 手机上运行的多标的行情监控 App。行情获取、策略判断、状态与日志存储、通知均在本地完成；没有后端、账号、API Key、下单或云同步。当前行情源为 OKX 公开现货，内置 BTC、ETH、SOL、DOGE、XRP。

当前固定配置：

- `applicationId = com.tzt.btcmonitor`
- Android 16 only：`minSdk = compileSdk = targetSdk = 36`
- Kotlin + Jetpack Compose + Coroutines
- OKX 公共 WebSocket：单连接动态订阅自选列表中的多个 `tickers`（官方 8443、标准 443、AWS 8443 自动轮换）
- OKX 公共 REST K 线：每个标的支持 `1m / 5m / 15m / 1H / 4H / 1D`，Compose Canvas 本地绘制当前价和提醒价
- 首页前台行情快照：Service 未运行时可通过公开 REST 刷新自选价格，不影响后台监控生命周期
- OkHttp WebSocket，20 秒 ping，1/2/5/10/30 秒退避重连
- 独立“行情获取测试”，逐端点验证握手、订阅、首个 Tick、价格、耗时和底层错误，不启动 Service 或策略
- DataStore 配置，前台服务，两个通知通道
- 多提醒列表：新增、编辑、启用/停用、删除；每条提醒独立按状态变化触发
- 完整诊断日志导出/分享，以及无需在 APK 保存 Token 的 GitHub Issue 提交
- GitHub Releases 检查、下载、SHA-256/包名/versionCode/签名校验和系统安装器

## 项目协作文档

后续开发采用文档驱动流程：Requirement → Planner → TASK → Developer → Reviewer → Android 16 真机测试。开始任务前先阅读：

- [`AGENTS.md`](AGENTS.md)：Agent、架构、开发、Build 与 Definition of Done 规则
- [`docs/PRD.md`](docs/PRD.md)：产品范围、当前阶段与明确不做
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：真实现状、目标架构和已知风险
- [`docs/UI_SPEC.md`](docs/UI_SPEC.md)：导航、页面与 Design System 规范
- [`docs/TODO.md`](docs/TODO.md)：按依赖和优先级拆分的唯一 TASK 入口
- [`docs/DECISIONS.md`](docs/DECISIONS.md)：跨模块技术决策（ADR）

当前执行状态和下一项任务以 `docs/TODO.md` 为准。本 README 只提供运行与使用概览，产品、架构与 TASK 以以上文档为准。

## 页面与提醒模型

- 首页是自选行情列表，只显示标的、最新价、24h 涨跌、提醒数量和连接状态。
- 点击行情卡片进入标的详情；K 线和提醒都属于该详情页，不再作为顶层 Tab。
- 自选列表可添加或移除；移除标的时会确认并同时删除它的提醒。

- 首页以列表管理提醒，可以新增、编辑、启用/停用和删除。
- 每条提醒绑定独立 `assetId`、symbol、名称、方向和价格。
- 每条提醒分别保存“上一次是否满足条件”；首次 Tick 只建立基线，不在启动时误报。
- 修改提醒后只重置该提醒的基线，其他提醒不受影响。
- 多条提醒可在同一个 Tick 分别触发，通知和日志都会保留对应提醒名称。
- v0.1.x/v0.2.x 的 BTC 提醒会自动归入 BTC-USDT 详情，不需要重新配置。
- 只要存在启用提醒且“全部监控”没有暂停，App 在前台时会自动启动 Foreground Service；全部提醒停用后自动停止。

## 架构判断

```text
行情列表 ──> 标的详情（K线 + 提醒） ──> MonitorViewModel
                                      ├── DataStore / UpdateManager / MarketDataProbe
                                      └── CandleRepository ──> OKX public REST
                      │
                      └──前台自动协调──> MarketMonitorService (specialUse FGS)
                                         ├── NetworkMonitor
                                         ├── MarketDataManager ──> MarketTick
                                         ├── StrategyEngine ──> StrategyResult
                                         ├── NotificationHelper
                                         └── MonitorStateStore / LogManager
```

WebSocket 不在 Activity 中。Service 只编排生命周期；连接与退避在 `MarketDataManager`，K 线历史数据在独立的 `CandleRepository`，前台诊断在 `MarketDataProbe`，网络切换在 `NetworkMonitor`，状态变化触发在 `StrategyEngine`，通知和更新也各自独立。图表请求失败不会停止后台监控。以后添加指标时，可以从 MarketTick/K 线聚合层进入 StrategyEngine，而不需要改 Service 的通知逻辑。

### 为什么是 `specialUse`

Android 16 的标准前台服务类型中，没有“用户主动启动、长期、实时、公开金融行情 WebSocket、本地提醒”这一类型：

- `dataSync` 是数据传输/同步任务，不适合无限期实时监控，而且有超时/配额语义。
- `remoteMessaging` 是设备间消息传递，不是交易所公开行情。
- `shortService` 有短时限制。
- 其他类型（位置、媒体、连接设备等）与实际用途不符。

因此 Manifest 同时声明 `FOREGROUND_SERVICE_SPECIAL_USE`、`foregroundServiceType="specialUse"`，并通过 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 写明准确用途。用户创建并启用提醒后，App 只在 Activity 位于前台时协调首次启动 Service；进入后台后由常驻通知保持用户可见。依据：[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[启动前台服务](https://developer.android.com/develop/background-work/services/fgs/launch)。若未来上架 Google Play，还需要接受 Play Console 对 specialUse 说明的审核；本项目当前为侧载。

## 项目结构

```text
app/src/main/java/com/tzt/btcmonitor/
├── MainActivity.kt
├── AppContainer.kt
├── ui/                  # Compose 页面和 ViewModel
├── service/             # MarketMonitorService
├── market/              # OKX WebSocket、K 线 REST、订阅、心跳、解析、重连
├── network/             # ConnectivityManager.NetworkCallback
├── strategy/            # 独立边沿触发策略
├── notification/        # FGS 与交易提醒通道
├── settings/            # Preferences DataStore
├── logging/             # 500 条 UI / 1000 条本地日志
├── update/              # GitHub Release 与安全安装
└── model/               # MarketTick、状态、策略结果
.github/workflows/release.yml
docs/ANDROID16_TESTING.md
docs/RELEASE.md
```

## Android Studio 编译

要求 Android Studio 中已安装：

1. Android SDK Platform 36 与 SDK Build-Tools。
2. JDK 17（Android Studio 的 Gradle JDK 选择 17 或兼容的内置 JBR）。
3. 联网下载 Gradle/Maven 依赖。

打开仓库根目录，等待 Gradle Sync，然后运行 `app`。命令行：

```powershell
./gradlew.bat test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 包使用 `com.tzt.btcmonitor.debug`，因此能与正式包并存。正式更新校验只允许相同 applicationId 和相同签名；Debug 包不能用正式 Release APK 覆盖。

项目刻意使用 Compose BOM `2026.06.01`、Lifecycle `2.10.0`、Core `1.17.0` 与 OkHttp `5.4.0`：更新的 Compose/Core/OkHttp Android artifact 已转到 compileSdk 37，而本项目的硬性边界是 API 36。AGP 8.13.2 + Gradle 8.13 支持 API 36，构建 JDK 为 17。

## 第一次使用

1. 安装并打开 App，允许通知权限。
2. 首页点击“添加监控标的”，选择内置的 OKX 现货标的。
3. 点击标的卡片进入详情，在 K 线下新增或编辑提醒。
4. “更多”中点击“测试通知”，单独确认声音、振动、锁屏和 Heads-up。
5. 有启用提醒时 App 会在前台自动启动监控；确认常驻通知、WebSocket 状态和列表价格更新时间。
6. 详情页选择 K 线周期；橙色虚线是该标的提醒价格，蓝线是当前价格。
7. “日志”页查看连接、网络、重连、策略和通知事件；行情端点测试、版本更新和仓库设置集中在“更多”。

策略严格按状态变化触发。Service 启动或修改策略后的第一条 Tick 只建立条件基线；只有观测到“不满足 → 满足”才通知。满足期间不重复；回到不满足后再次跨越才再次通知。

## 系统限制（必须理解）

Foreground Service 提高存活优先级，但不是“永久在线”保证：

- Android Doze 会暂停普通网络访问；前台服务并不能获得持续 WebSocket 网络豁免。维护窗口或退出 Doze 后，网络回调/OkHttp 失败回调会触发重连。[Doze 官方说明](https://developer.android.com/training/monitoring-device-state/doze-standby)
- `START_STICKY` 是进程被回收后的重建请求，不保证立即重启，也不能绕过用户“强行停止”或 Android 的 Active apps/Task Manager 停止操作。
- 厂商电池管理、数据节省、VPN、防火墙、交易所地区限制都可能影响连接。
- 用户拒绝通知权限时，Android 仍可能在 Active apps 中显示 FGS，但普通通知抽屉和交易提醒不可用。
- 本项目不请求忽略电池优化、不持有永久 WakeLock，也不使用精确闹钟伪造保活。

因此此 MVP 的价值正是测量你的特定 Android 16 手机：详见 [Android 16 / Doze 真机测试](docs/ANDROID16_TESTING.md)。发布与签名见 [Release 指南](docs/RELEASE.md)。

## 数据与安全

- 实时行情来自 OKX 公共 WebSocket，K 线来自 OKX 公共 REST，无 API Key；应用中没有下单接口。
- 策略设置只在 DataStore，本地日志位于 App 私有目录。
- App 不包含 GitHub PAT。更新仓库必须公开，或由另一个公开仓库仅发布 APK/checksum。
- “GitHub 日志”会打开预填 Issue，由用户检查后提交；应用不会在后台静默上传运行数据。
- 下载后必须依次通过 SHA-256、包名、递增 versionCode、当前签名证书四项检查，才会打开 Android 系统安装确认页。
- 安装未知应用权限由用户在系统设置授权；应用不会绕过系统确认。
- Android 正在为 2027 全球 rollout 推进认证设备的开发者/包名验证；建议用最终签名证书在 Android Developer Console 注册本应用。详见 Release 指南。

## 主要官方依据

- [Android 16 前台服务变化](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Foreground service types / specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [通知与 Heads-up/锁屏](https://developer.android.com/develop/ui/compose/notifications)
- [通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [`canRequestPackageInstalls`](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())
- [安全共享 APK 的 FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)

GitHub 从建仓库、配置签名 Secrets 到发布第一个版本的完整步骤见 [GitHub 设置指南](docs/GITHUB_SETUP.md)。

本软件只是本地监控工具，不构成交易建议，也不保证提醒在 Doze、断网或系统回收期间实时送达。

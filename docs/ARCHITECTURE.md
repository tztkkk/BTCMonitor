# Architecture

## 1. Scope and Evidence

本文以 v0.3.0 当前源码、Gradle 配置、Manifest、单元测试和已有 Android 16 测试文档为依据。`Current Architecture` 描述事实；`Target Architecture` 是渐进方向，不代表对应模块已经存在，也不授权一次性重构。

## 2. Current Architecture

### 2.1 Runtime Overview

```text
MainActivity
    └── MonitorApp (Compose)
          ├── 行情列表 / 标的详情 / 提醒 CRUD / K线
          ├── 日志
          └── 更多 / 诊断 / 更新
                 │
                 ▼
          MonitorViewModel
          ├── SettingsRepository ── Preferences DataStore
          ├── CandleRepository ──── OKX REST
          ├── MarketDataProbe ───── OKX WebSocket diagnostics
          ├── UpdateManager ─────── GitHub Releases
          └── MarketMonitorService start/stop

MarketMonitorService (specialUse FGS)
    ├── NetworkMonitor
    ├── MarketDataManager ───────── single OKX WebSocket / multi-symbol
    ├── StrategyEngine ──────────── price-threshold edge evaluation
    ├── NotificationHelper ──────── Android notifications
    └── MonitorStateStore / LogManager
```

`AppContainer` 是进程级手工 service locator，持有 application context 生命周期的 Settings、Logs、MonitorState、Diagnostics、Candles、Notifications 和 Updates。Service 自己创建 `MarketDataManager`、`NetworkMonitor` 和 `StrategyEngine`。

### 2.2 Capability Inventory

| Area | Classification | Current evidence |
| --- | --- | --- |
| UI | Prototype | Compose + Material 3；`MonitorScreen.kt` 包含 1066 行、所有页面和大部分组件 |
| Navigation | Prototype | 顶层 `行情/日志/更多` Tab 与本地 `selectedAssetId` 详情状态；无 Navigation Compose、route 或 deep link |
| ViewModel | Prototype | 单 `MonitorViewModel` 聚合行情、K线、提醒、诊断、通知测试、设置与更新操作 |
| Repository | Prototype | `CandleRepository` 和 `SettingsRepository` 存在；没有统一 `MarketRepository` 接口 |
| Network/HTTP | Existing | OkHttp 直接调用 OKX REST 与 GitHub API；无 Retrofit |
| WebSocket | Prototype | `MarketDataManager` 单连接动态订阅多个 ticker，端点轮换、ping/pong、退避重连；全局派发节流会造成跨 symbol 饥饿风险 |
| Database | Missing | 无 Room/SQLite 业务数据库 |
| Local storage | Prototype | Preferences DataStore 保存自选、价格提醒、暂停状态与仓库设置；LogManager 写 App 私有日志 |
| Background | Prototype | `specialUse` FGS、`START_STICKY`、`stopWithTask=false`；无 WorkManager、Boot Receiver、WakeLock、忽略电池优化请求 |
| Notification | Prototype | 低重要度常驻通道与高重要度价格提醒通道；点击只打开 MainActivity，不定位具体信号/标的 |
| Rule | Prototype | `AlertConfig` 表示价格阈值规则，但没有通用 Rule 类型、timeframe 或调度模型 |
| Strategy | Prototype | `StrategyEngine` 只处理价格阈值且保留每条提醒的内存边沿状态 |
| Indicator | Missing | 没有 EMA/RSI/MACD/ATR/Volume 指标计算模块 |
| Signal | Missing | `StrategyResult` 是瞬时结果；没有统一 Signal、状态或历史存储 |
| Diagnostics | Existing | 日志、端点探测、分享/GitHub Issue、系统/电池/通知状态采集 |
| Update | Existing | GitHub Release 检查、下载与安全校验安装 |

`Existing` 表示能力明确存在；`Prototype` 表示可工作但边界、可靠性或产品完整度未达到目标；`Missing` 表示当前源码没有该层或能力。

### 2.3 Presentation

- `MainActivity` 负责 Compose 启动、通知权限请求、未知来源设置和外部 Intent。
- `MonitorApp` 直接组合全部 UI，使用 `collectAsStateWithLifecycle` 观察 StateFlow。
- Dark ColorScheme 定义在 `MonitorScreen.kt`；部分金融语义色和图表色在组件内硬编码。
- K 线由 Compose Canvas 本地绘制，展示最近 60 根蜡烛、当前价线和提醒价线；`MarketCandle.volume` 尚未展示。
- 详情选择和 Tab 状态仅用 `remember` 保存；没有进程恢复、URL 路由或通知 deep link。

### 2.4 Market Data

当前存在三条并列访问路径：

1. `MarketDataManager`：Service 生命周期的 OKX WebSocket ticker。
2. `CandleRepository`：UI/ViewModel 使用的 OKX REST K 线与 ticker 快照。
3. `MarketDataProbe`：独立诊断 WebSocket 端点，不启动 Service 或策略。

长期常驻行情连接只有 Service 中一条，符合“避免页面重复连接”的方向；诊断连接是用户主动、短生命周期的例外。

重要现状：

- 动态订阅 `settings.assets` 的全部 symbol，但只有存在启用提醒且未全局暂停时才连接。
- 行情状态写入进程内 `MonitorStateStore`。
- Service 不运行时，首页由 ViewModel 并行获取 REST ticker 快照。
- `MarketDataManager.lastDispatchMillis` 是全局值。多 symbol 消息在同一秒到达时会被统一节流，可能使部分 symbol 不能更新或评估策略。这是 P0 正确性问题。

### 2.5 Rule and Strategy

`AlertConfig` 包含 `id/name/assetId/symbol/enabled/direction/threshold`。`StrategyEngine`：

- 按 alert ID 维护上一次条件状态。
- 首次 Tick 只建立基线。
- 仅在条件由不满足变为满足时触发。
- 配置变化只重置变化规则的基线。
- 状态只在内存中，Service/进程重建后重新建立基线。

当前名称 `StrategyEngine` 超前于实际能力：它是经过测试的价格提醒规则执行器，不是通用指标策略引擎。

### 2.6 Background Lifecycle

当前分类：**Prototype，保留并继续验证**。

```text
有启用提醒 + 未暂停 + Activity 前台协调
    ↓
startForegroundService(ACTION_START)
    ↓
MarketMonitorService / specialUse / ongoing notification
    ├── NetworkCallback
    ├── WebSocket
    └── Strategy evaluation
```

- `START_STICKY` 请求系统在非用户主动停止的进程回收后重建 Service，但不保证时机。
- `stopWithTask=false` 且 `onTaskRemoved` 不主动停止，因此划掉 Activity 时尝试继续。
- `NetworkMonitor` 在网络变化时使连接失效并重连。
- Service 的 `SupervisorJob + Dispatchers.IO` 在 `onDestroy` 取消。
- 用户强行停止、Android Task Manager 停止、厂商电池管理、Doze 网络暂停均不能由当前方案绕过。
- 没有开机恢复；重启手机后不会自动恢复 Service。
- 没有 WorkManager；当前持续 WebSocket 场景也不能简单替换为周期 Work。
- 没有持久化运行时边沿状态和 Signal，因此离线/被回收窗口内的穿越可能无法重建。

### 2.7 Notification

- `NotificationHelper` 只负责通道与通知构建/发送，Strategy 不直接调用 Android API。
- Service 将触发的 `StrategyResult` 转交给 NotificationHelper，职责已初步解耦。
- Android 13+ 通知权限由 Activity 在启动监控或测试通知前请求。
- 价格通知没有稳定 Signal ID、去重键、目标 route 或持久化记录。
- 频道创建后声音/重要度由系统管理，后续配置 UI 应引导用户进入系统设置而不是假设可覆盖。

### 2.8 Persistence

- `SettingsRepository` 使用 Preferences DataStore，并把自选和提醒列表编码为 JSON 字符串。
- 具备旧单提醒键迁移到列表默认值的兼容逻辑。
- 适合当前小规模配置，但不适合可查询的 Signal 历史、复杂组合 Rule 或 schema 关系。
- 当前没有明确 schema version、事务化关系模型或数据库迁移测试。

### 2.9 Tests

当前 JVM 单元测试覆盖：

- 价格提醒边沿触发和多提醒独立状态。
- Alert/WatchAsset JSON round-trip。
- OKX REST ticker/K 线解析。
- OKX WebSocket 协议与端点选择。

已配置 Android/Compose 测试依赖，但当前源码树没有 instrumentation/UI 测试。后台、通知、Doze、网络切换依赖 `docs/ANDROID16_TESTING.md` 的真机矩阵。

### 2.10 Audit Build Validation (2026-08-28)

本次文档工程化完成后执行了 `gradlew.bat assembleDebug`：

- 系统默认 `JAVA_HOME` 指向 Java 8，第一次调用在解析 AGP 8.13.2 时因 JVM 版本过低失败。
- 临时使用已安装的 Corretto 21 后，Gradle 8.13 正常启动并越过 AGP/JVM 检查。
- 随后因当前命令行环境没有 `ANDROID_HOME` / `ANDROID_SDK_ROOT`、仓库没有本机 `local.properties`，无法找到 Android SDK，构建在源码编译前停止。
- 该结果是本机环境阻塞，不是本次仅 Markdown 修改引入的编译错误；也不能据此宣称当前 HEAD 已在本环境构建通过。

复验需要配置 Android SDK Platform 36 路径，并使用 JDK 17 或兼容的更新 JDK，然后重新运行：

```powershell
.\gradlew.bat assembleDebug
```

## 3. Target Architecture

目标是渐进形成清晰边界：

```text
Presentation
│
├── Screen
├── Component
├── Navigation
└── ViewModel / UiState
        │
        ▼
Domain
│
├── Model
├── Indicator
├── Rule
├── Strategy
└── Signal
        │
        ▼
Data
│
├── Repository
├── RemoteDataSource
├── LocalDataSource
└── Database
```

不要为了得到目录形状一次性移动所有现有类。先通过接口和测试固定行为，再按 TASK 迁移调用方。

### 3.1 Target Market Data Flow

```text
Exchange / Market API
          │
          ▼
  Remote MarketDataSource
          │
          ▼
     MarketRepository
          │
      ┌───┴─────────┐
      ▼             ▼
 UI Market State  Strategy Engine
                        │
                        ▼
                      Signal
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
       Signal Repository   Notification Dispatcher
```

#### MarketRepository responsibilities

- 向 UI 和策略暴露统一的 quote/candle stream 或查询接口。
- 对常驻实时连接实施单一 owner、订阅合并和按 symbol 隔离的节流/聚合。
- 隔离 OKX URL、JSON 和重连细节，为将来其他资产数据源保留边界。
- 区分实时流、按需快照、历史 K 线和诊断探测，不把诊断连接混入生产状态。
- 公开可观察的 freshness、connection、source 和 error 状态。
- 历史 K 线查询支持明确的分页游标（例如 `before`）与去重/排序契约，使图表可以在左边界继续加载且保持视口锚点。

以下结构必须避免：

```text
Home → WebSocket
Market → WebSocket
Strategy → WebSocket
Service → WebSocket
```

#### TASK-004 contract baseline

`domain/market` 已建立 provider-neutral 的 `MarketRepository` 最小契约和纯 Kotlin 模型，覆盖稳定 instrument ID、symbol、timeframe、实时 quote、connection、逐 instrument freshness、结构化 error 与向前历史分页。历史页统一约定：

- `beforeExclusiveMillis` 是排他游标，只返回更早的 Candle。
- 单页按 `openTimeMillis` 从旧到新排序，同一开盘时间只保留一条。
- `hasMore = true` 时，`nextBeforeExclusiveMillis` 等于当前页最早 Candle 的开盘时间。
- suspend 请求的协程取消必须向上传播，不转换为普通行情错误。

本 TASK 只固定边界，生产调用方尚未迁移。后续生产接入点为：

- `WatchAsset` 适配为 `MarketInstrument`；保留 provider-qualified ID 与数据源 symbol 的区别。
- `CandleTimeframe.apiValue` 只留在 OKX adapter 内，映射到不含供应商字段的 `MarketTimeframe`。
- 继续复用现有纯模型 `MarketCandle`；`AssetQuote`、`MarketTick` 和 `WebSocketStatus` 在 adapter 边界映射为新 quote/realtime state。
- TASK-010 将 `CandleRepository.loadRecent/loadQuote` 包装为分页/快照实现，并处理 OKX cursor、错误和取消映射。
- `MarketDataManager` + `MarketMonitorService` 在迁移完成前仍是唯一生产 WebSocket owner；接入 Repository 时必须复用该连接，禁止新增第二条常驻连接或长期双写 `MonitorStateStore`。
- `MarketDataProbe` 继续作为用户主动触发的短时诊断，不进入生产 Repository 状态。

### 3.2 Reusable Interactive Chart Boundary

图表是 Presentation 组件，但其输入模型、视口运算和事件契约必须可复用：

```text
MarketRepository / ViewModel
        │
        ├── candles / loading / hasMore
        ├── alert line presentation models
        └── chart viewport state
        ▼
InteractiveCandleChart
        │
        ├── onLoadOlder(anchor)
        ├── onCreateAlert(price)
        ├── onMoveAlert(alertId, price)
        └── onViewportChanged(viewport)
```

约束：

- `InteractiveCandleChart` 不依赖 `MonitorViewModel`、OKX、DataStore、`AlertConfig` 或 Notification。
- Candle、Viewport、Crosshair、AlertLine/Marker 使用 UI/Domain 可复用模型；供应商 symbol 和 JSON 不进入图表。
- 平移、横向缩放、纵向价格缩放、命中测试和坐标换算集中实现，不散落到 Screen。
- 历史分页由事件请求 ViewModel/Repository；图表不直接发网络请求。
- 提醒线拖动只在手势结束输出一次保存事件；持久化成功/失败由上层状态反馈。
- 图表库必须先用独立 Demo 验证。验收维度包括 Compose/Android 16、许可证、维护状态、历史分页、双轴缩放、十字光标、自定义提醒线与拖拽、性能和可测试性。
- 若没有库同时满足核心能力，采用可复用 Compose Canvas 实现；不得为了“使用库”牺牲提醒线扩展能力。

### 3.3 Target-price Alert Domain

当前 `AlertConfig(direction, threshold)` 将演进为不含方向的到价提醒。核心判断是相邻有效行情对目标价的双向穿越：

```text
previousPrice < target && currentPrice >= target
or
previousPrice > target && currentPrice <= target
or exact target contact
```

Domain evaluator 负责：

- 每条提醒独立维护上一次有效价格所在侧。
- 新建或修改目标价后的首 Tick 只建立基线。
- 所有提醒读取同一个全局 cooldown 设置（1/5/15/30/60 分钟，默认 5 分钟）。
- 冷却期间继续更新所在侧但不产生通知。
- 冷却结束后不因仍在目标价另一侧自动触发，必须发生新的穿越。
- Clock/时间来源可注入，以便确定性测试。

迁移必须保留旧提醒的 ID、name、assetId/symbol、threshold 和 enabled，丢弃原 direction；使用新 schema key/version 或等价的可测试迁移机制，不允许静默丢失或重复提醒。

#### TASK-005 domain baseline and migration design

`domain/alert` 已建立不含 direction、Android、Compose、OKX、DataStore 或 Notification 依赖的 `TargetPriceAlert` 与 `TargetPriceAlertEvaluator`。Evaluator 使用注入的 `Clock`、共享的合法 cooldown 配置和每条 alert 独立的内存状态；新建、改价或重新启用后的首个有效 Tick 只建立基线。冷却期间发生穿越时更新所在侧但不通知，冷却结束后不补发，必须等待下一次新穿越。

TASK-011 的持久化迁移按以下设计执行，本 TASK 不修改 DataStore：

1. 为新到价提醒使用显式 schema version 或新 key；只有新 schema 不存在时才读取旧 `AlertConfig` JSON。
2. 每条旧提醒保留 `id`、`name`、`assetId`、`symbol`、`enabled` 和 `threshold`，将 `threshold` 映射为 `targetPrice`，忽略 `direction`。
3. 迁移结果按 `id` 去重并一次性写入新 schema；再次启动看到新 schema 后不得重复导入，保证幂等。
4. 任一条记录无效时不得清空其他有效提醒；迁移测试必须覆盖混合有效/无效记录、重复 ID 和 round-trip。
5. 全局 cooldown 单独持久化，只接受 1、5、15、30、60 分钟；缺失或非法值回退 5 分钟。
6. 生产 `Service`/`StrategyEngine` 只有在 TASK-011 完成 schema 迁移后才切换到新 evaluator；切换时不双写旧、新策略状态，首 Tick 重新建立内存基线。

### 3.4 Indicator

Indicator 是纯计算或明确状态化的可复用能力，例如 EMA、RSI、MACD、ATR、Volume。要求：

- 输入/输出、warm-up 数量、缺失数据和精度规则明确。
- 不依赖 Android UI、Notification 或网络实现。
- 同一指标只实现一次，并有固定样例的单元测试。
- 指标计算与 timeframe/candle ordering 契约明确。

### 3.5 Rule and Strategy

目标接口应允许每个策略作为独立实现注册或组合，避免巨大条件分支：

```text
RuleConfig + MarketContext
          ↓
Strategy implementation
          ↓
EvaluationResult
          ↓
Signal?
```

未来 PriceAlert、EMA、RSI、Breakout、PriceAction、SMC 应通过统一契约扩展，而不是形成不断增长的：

```text
if strategy == EMA
else if strategy == RSI
else if strategy == SMC
```

通用抽象必须在第二种真实策略出现前由 ADR 确认，避免只为当前价格提醒过度设计。

### 3.6 Signal

Signal 是策略判断与用户通知之间的稳定业务事实。候选字段：

```text
id
ruleId
instrumentId
symbol
timeframe
strategyType
signalType
triggerPrice
timestamp
message
strength
status
```

具体字段、枚举和数据库 schema 必须由独立 TASK/ADR 确认。Signal 至少应支持：唯一标识、来源规则、时间、触发价、状态、持久化、列表查询和通知关联。

### 3.7 Notification

边界必须保持：

- Strategy 决定 **什么时候产生 Signal**。
- Signal Repository 决定 **如何保存和去重业务事实**。
- Notification Dispatcher 决定 **如何通知用户**。

Notification 不重新计算策略；Strategy 不调用 Android Notification API。通知点击应携带 Signal ID，并在导航能力建立后进入信号或对应标的详情。

### 3.8 Target Presentation

- 将 1066 行 `MonitorScreen.kt` 渐进拆成 screen/component/theme 文件。
- ViewModel 以 feature/use-case 边界拆分，避免单类继续聚合所有操作。
- 先集中 Design System，再统一卡片、状态和反馈组件。
- 当五个长期顶层目的地开始落地时再通过明确 TASK 引入/实现导航；当前文档任务不重写 Navigation。

## 4. Dependency and Ownership Rules

| Concern | Current owner | Target owner |
| --- | --- | --- |
| WebSocket lifecycle | MarketMonitorService + MarketDataManager | Background coordinator + MarketRepository/RemoteDataSource |
| REST Kline/quote | CandleRepository | MarketRepository + RemoteDataSource |
| Runtime market state | MonitorStateStore | MarketRepository exposed state |
| Price alert config | SettingsRepository | RuleRepository/LocalDataSource |
| Rule evaluation | StrategyEngine | Strategy implementations/engine |
| Signal persistence | None | SignalRepository/Database |
| Android notification | NotificationHelper | Notification Dispatcher consuming Signal |
| UI state | MonitorViewModel + local remember | Feature ViewModels/UiState + Navigation state |

同一阶段只允许一个权威 owner。迁移期间如需适配层，要标记弃用路径和删除条件，不得长期双写。

## 5. Known Architecture Risks

### Critical

1. **多 symbol 全局派发节流可能漏评估提醒。** `MarketDataManager` 用单一 `lastDispatchMillis` 对所有 symbol 限流，1 秒窗口内其他 symbol 的消息会被丢弃。必须改为按 symbol 节流或在订阅层明确公平聚合，并增加多 symbol 测试。

### Major

1. **UI 与 ViewModel 职责集中。** 单 Screen 文件和单 ViewModel 增加冲突、评审和并行开发风险。
2. **行情访问没有统一 Repository 契约。** REST、WebSocket、UI 状态分别由多个具体类协调，未来加入市场/策略容易重复实现或泄漏数据源细节。
3. **Signal 不存在。** 通知触发事实不可查询、不可恢复、不可 deep link，诊断日志不能替代业务历史。
4. **后台仍是 Prototype。** 无手机重启恢复，Doze/系统回收窗口无法保证连续网络或补偿错过的行情；应通过产品语义和真机测试处理，而不是承诺永久在线。
5. **复杂配置不适合继续使用 JSON-in-Preferences 扩张。** Rule/Signal 增长前需决定结构化本地存储方案。

### Minor

1. Design tokens 与金融语义色散落在 UI 文件。
2. 手工 Tab/详情状态不支持进程恢复和通知定向导航。
3. K 线模型含 volume，但 UI 没有成交量视图。
4. `StrategyEngine` 命名与当前仅价格提醒的实际职责不完全一致。

## 6. Change Strategy

推荐顺序：

1. 明确当前阶段 Market、Chart、Target-price Alert 的边界，并先修复多 symbol 派发正确性。
2. 建立支持历史分页的最小 MarketRepository 契约，避免图表直接依赖 OKX/网络。
3. 用独立 Demo 完成图表库技术验证并记录 ADR。
4. 建立可复用图表状态/API，再逐项实现平移、缩放、十字光标和分页。
5. 用纯 Domain evaluator 实现双向穿越与全局 cooldown，并完成旧提醒迁移。
6. 接入图上创建、提醒线拖动和监控设置，最后执行 Android 16 端到端验证。

Signal、Indicator、完整 Strategy、Navigation 和 Design System 的进一步演进保留在候选区，由本阶段结果决定下一轮，不在当前实施队列中。

每一步都必须保持现有价格提醒闭环可构建、可测试、可真机验证。

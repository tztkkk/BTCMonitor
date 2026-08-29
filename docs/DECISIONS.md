# Architecture Decision Records

本文件记录会影响多个 TASK、模块边界或长期兼容性的决策。`Proposed` 不是实现授权；需在对应 TASK 开始前确认。已由当前代码和产品约束明确采用的方案标记为 `Accepted`。

---

## ADR-001 — 统一行情连接管理

**Status:** Accepted（方向）；目标 Repository 接口仍待 TASK 设计

### Context

当前 `MarketMonitorService` 持有一个 `MarketDataManager`，通过一条 OKX WebSocket 动态订阅多个 symbol。UI 的历史 K 线和前台快照由 `CandleRepository` 请求 REST；`MarketDataProbe` 是用户主动运行的短时诊断连接。

### Problem

随着首页、行情、策略和后台监控扩展，如果各自创建 WebSocket，将增加连接、资源、状态不一致和重复策略评估。当前 REST、WebSocket 和状态存储也没有统一 MarketRepository 契约。

### Options

1. 每个页面/策略拥有自己的行情连接。
2. 保留单一生产连接 owner，以 MarketRepository 统一订阅、状态和数据源边界；诊断探测保持隔离。
3. 所有行情移到云端服务器再推送给 App。

### Recommendation

选择 Option 2。保留当前 Service + 单连接的工作实现，先修复按 symbol 派发正确性，再通过小 TASK 建立 MarketRepository/RemoteDataSource 契约并迁移 REST 与实时状态。诊断连接不进入生产连接池。

### Consequences

- UI 和策略共享一致行情与连接状态。
- 订阅合并、freshness、节流和重连规则需要集中且可测试。
- Service 与 Repository 的生命周期 owner 必须明确，迁移期不得形成双连接/双写。
- 暂不引入云端成本和账号体系。

---

## ADR-002 — Android 16 后台监控采用用户可见的 specialUse Foreground Service

**Status:** Accepted（当前侧载产品范围）

### Context

产品需要用户创建启用提醒后持续消费公开金融行情 WebSocket。Android 16 标准 FGS 类型没有准确覆盖无限期、用户可见的公开金融行情监控。当前 Manifest 声明 `FOREGROUND_SERVICE_SPECIAL_USE` 和准确 subtype，Service 使用常驻通知、`START_STICKY`、`stopWithTask=false` 和网络回调。

### Problem

需要在 Android 系统限制内提高后台存活和用户可见性，同时不能虚假承诺永久在线或滥用其他 FGS 类型。

### Options

1. 保留 specialUse FGS。
2. 用 WorkManager 周期任务替代持续 WebSocket。
3. 使用错误的 dataSync/remoteMessaging 类型。
4. 请求忽略电池优化、WakeLock 或精确闹钟模拟保活。

### Recommendation

当前选择 Option 1。WorkManager 可用于未来可中断/补偿任务，但不是持续 WebSocket 的直接替代。Options 3/4 不采用。若进入 Google Play 发布范围，重新评估 specialUse 审核与产品声明。

### Consequences

- 用户始终看到常驻通知并可停止监控。
- Doze、强行停止、系统 Task Manager、重启和厂商电池策略仍可能中断监控。
- 每个后台相关 TASK 必须执行 Android 16 真机矩阵并准确呈现限制。
- 当前不自动开机恢复；是否需要恢复必须另立产品决策。

---

## ADR-003 — 策略使用可扩展实现，不建立无限增长的类型分支

**Status:** Proposed

### Context

当前只有价格阈值提醒，`StrategyEngine` 能按 alert ID 独立维护边沿状态。长期需要 EMA、RSI、Breakout、Price Action 和 SMC。

### Problem

直接在单一 Engine 内不断添加 `if/else` 或 `when(strategyType)` 会耦合配置、指标、生命周期和通知，并使测试与并行开发困难；但在只有一个策略时提前设计完整插件框架也会过度抽象。

### Options

1. 在当前 `StrategyEngine` 持续加入所有策略分支。
2. 在第二种真实策略引入前定义最小 Strategy 接口、注册/工厂边界和统一 EvaluationResult/Signal。
3. 立即建立完整动态插件系统。

### Recommendation

选择 Option 2。先将当前 Price Alert 行为用测试固定；定义 Rule/Signal 后，在实现首个指标策略的 TASK 中确认最小接口。暂不采用运行时插件系统。

### Consequences

- 各策略可独立实现和测试，Indicator 可复用。
- 需要明确策略实例状态、warm-up、timeframe 和配置迁移。
- 通用抽象延迟到有第二个真实用例时，降低过度设计风险。

---

## ADR-004 — Rule 与 Signal 的本地存储方案

**Status:** Proposed

### Context

当前 Preferences DataStore 以 JSON 字符串保存小规模自选和价格提醒，适用于 Prototype。Signal 历史尚不存在；未来 Rule 可能包含 timeframe、指标参数、组合条件与状态。

### Problem

继续把复杂 Rule 和可查询 Signal 历史塞进 Preferences 会缺少关系、索引、分页、迁移和事务保障。立即引入数据库又会新增依赖和迁移成本。

### Options

1. 所有数据继续 JSON-in-Preferences。
2. DataStore 保留简单偏好；结构化 Rule/Signal 使用 Room。
3. 自建 SQLite 层。
4. 云端存储。

### Recommendation

倾向 Option 2，但在 Signal schema TASK 中用查询需求、预计数据量、迁移策略和依赖成本确认后再 `Accepted`。现有提醒不得在没有迁移测试时直接搬迁。

### Consequences

- 简单偏好与业务记录职责清晰。
- 若采用 Room，需要新增依赖、schema、migration 与测试，并迁移已有 AlertConfig。
- 在 ADR 接受前，不为未来字段盲目增加数据库框架。

---

## ADR-005 — 行情数据源保持可替换，当前实现继续使用 OKX 公开 API

**Status:** Accepted（当前实现）；多市场供应商选择未决

### Context

当前五个 Crypto 标的使用 OKX public WebSocket 和 REST，无 API Key。长期范围还包括 GOLD、NASDAQ、S&P 500 和股票，单一加密交易所无法覆盖全部市场。

### Problem

业务模型若直接依赖 OKX symbol、端点和 JSON，将使其他资产接入困难；现在立即选择所有市场供应商又缺少成本、授权、稳定性和地区可用性依据。

### Options

1. 把 OKX 当作所有未来市场的固定抽象。
2. 保留 OKX 作为当前 Crypto provider，通过 instrument/provider 和 DataSource 边界隔离；逐市场另做供应商评估。
3. 当前立即接入多个供应商。

### Recommendation

选择 Option 2。当前不更换工作中的 OKX 实现，不在 PRD 假定黄金、指数或股票供应商。Provider 选择须分别评估可靠性、授权/再分发、实时性、费用、认证和地区限制。

### Consequences

- 当前闭环保持稳定。
- Instrument ID、展示 symbol 与 provider symbol 需要逐步分离。
- 新市场接入前必须先有数据源 TASK/ADR，不能把伪数据标成实时行情。

---

## ADR-006 — 交互式 K 线采用可复用 Compose Canvas

**Status:** Accepted（2026-08-29，TASK-007）

### Context

当前 K 线由单个 Compose Canvas 静态绘制。下一阶段需要历史平移和分页、双指时间缩放、价格轴缩放、十字光标/OHLC、自定义到价提醒线创建与拖拽。用户允许使用成熟开源库，也允许独立实验页面；若无合适库，可继续扩展 Canvas。

### Problem

直接把某个库接入正式详情页，可能在许可证、维护状态、Compose/Android 16 兼容性或自定义提醒线交互上遇到不可逆约束。直接自研全部图表能力则有手势、性能和维护成本。

### Options

1. 选用满足核心能力的成熟开源 Android/Compose 图表库，并用适配层隔离。
2. 基于 Compose Canvas 构建可复用交互式图表组件。
3. 在未验证前直接替换生产图表。

### Candidate evidence

以下信息于 2026-08-29 从项目官方仓库、Release 和许可证文件核验：

| Candidate | Version / license / maintenance | Verified strengths | Blocking gap for Monitor |
| --- | --- | --- | --- |
| [Vico](https://github.com/patrykandpatrick/vico) | `3.3.1`（2026-08-28），Apache-2.0，活跃维护 | 原生 Compose、Candlestick、横向滚动/缩放、Marker；官方说明历史前插时保持可见 x-range | `VicoZoomState` 只缩放横向 layer dimensions；`HorizontalLine` 是只绘制 Decoration，没有提醒线命中/拖动事件。补齐纵向价格轴缩放与拖线仍需在库手势和内部坐标之上维护第二套交互层。 |
| [TradingView Lightweight Charts Android](https://github.com/tradingview/lightweight-charts-android) | `5.2.0`（2026-06-17），Apache-2.0 并要求产品 attribution，活跃维护 | 金融 K 线、滚动/双轴缩放、Crosshair、逻辑范围和价格坐标 API 成熟 | Android 包装器依赖支持 ES2020 的 WebView；官方 README 明确尚未暴露 custom plugin authoring wrapper。可拖动提醒线需要额外 JavaScript/plugin 与 Android bridge，增加输入、生命周期、测试和供应链边界。 |
| [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | `3.1.0`（2019-03-20），Apache-2.0；仓库未归档但稳定 Release 已多年未更新 | CandleStickChart、平移、缩放和 LimitLine 能力成熟 | View-based + JitPack；与 Compose 需要 AndroidView 互操作。LimitLine 没有满足本项目语义的拖动保存契约，仍需自建命中层；版本和 Android 工具链基线过旧。 |
| [chartkit](https://github.com/ccBiver/chartkit) | README 坐标 `0.1.8`，MIT，2026-07 有提交但没有正式 GitHub Release | 纯 Compose K 线、横向滚动/缩放、持久 Crosshair/OHLC、`onLoadMore`、前插保持位置 | API 没有纵向价格范围缩放、任意水平提醒线输入或提醒线命中/拖动回调；成熟度不足以为这些核心能力建立长期依赖。 |
| [MPAndroidChart-Compose](https://github.com/Amir-yazdanmanesh/MPAndroidChart-Compose) | README 描述 `v4.0.0` Compose 重写，但官方 latest Release 仍为 `v3.2.1`；Apache-2.0 | README 声明 Candlestick、pan/pinch、Marker 和 LimitLine | 文档坐标与正式 Release 不一致，4.x 稳定制品证据不足；同样没有可拖动提醒线和项目所需事件边界证据。 |

没有候选同时通过以下硬门槛：纯 Android/Compose 可控输入、时间与价格双轴缩放、持久十字光标、历史前插稳定、任意提醒线命中/拖动、手势优先级可测试，以及不让库类型扩散到 TASK-006 API 之外。

### Spike evidence

TASK-007 在 `app/src/debug` 建立 `ChartTechnologySpikeActivity`，仅合入 Debug Manifest，不存在生产导航入口，也不修改正式 `CandlestickChart`：

- 通过现有 `AppContainer.candles` / `CandleRepository` 加载真实 BTC-USDT 1m `MarketCandle`，再使用 TASK-006 `toChartCandles()` 适配。
- Compose Canvas 只接收 `InteractiveCandleChartState`；单指平移、双指时间缩放、右侧价格轴拖动缩放、长按 Crosshair/OHLC、提醒线命中与拖动均转换为 TASK-006 action/event。
- “模拟前插历史”只验证 `RequestLoadOlder` / `OlderCandlesLoaded` 后的稳定时间锚点；正式网络分页明确保留给 TASK-010。
- JVM 测试覆盖平移边界、缩放焦点、价格坐标往返、价格范围缩放、Crosshair 命中和 10,000 Candle 下最多 300 根的有界绘制窗口。
- Xiaomi 25102RKBEC（Android 16 / API 36）已验证默认真实行情路径可加载 200 根 BTC-USDT 1m Candle 并完成基础渲染；`connectedDebugAndroidTest` 使用 debug-only 确定性 Candle fixture 验证平移、双指时间缩放、价格轴缩放、Crosshair/OHLC、提醒线拖动结束单次事件和历史前插，避免外部 OKX 网络波动导致手势回归不稳定。
- Debug APK 可用以下方式独立启动，不暴露正式产品入口：

  ```powershell
  adb shell am start -n com.tzt.btcmonitor.debug/com.tzt.btcmonitor.ui.chart.spike.ChartTechnologySpikeActivity
  ```

### Recommendation

选择 Option 2：以 TASK-006 自有模型和事件为边界，使用可复用 Compose Canvas 实现正式交互式图表。TASK-007 不新增第三方依赖，也不替换生产图表；正式渲染、完整手势和分页仍分别由 TASK-008、TASK-009、TASK-010 实施。拒绝 Option 3。

### Consequences

- 不增加 JitPack、WebView 图表桥或第三方 Chart 类型，现有依赖与 API 36 边界不变。
- 项目承担坐标换算、手势仲裁、绘制性能与可访问性摘要的维护成本；这些必须由纯函数测试和 Android 16 真机手势矩阵保护。
- Debug Spike 在生产组件通过 TASK-008～010 验收前保留为隔离证据；之后可由独立清理 TASK 删除，不能成为第二套长期生产图表。
- 若未来候选库补齐纵向缩放和可拖动 Decoration/Primitive API，必须重新验证并用新 ADR 才能替换本决定。

---

## ADR-007 — 价格提醒统一为双向穿越到价提醒

**Status:** Accepted

### Context

当前提醒要求用户选择 `ABOVE_OR_EQUAL` 或 `BELOW_OR_EQUAL`。产品决定简化为“到达某个价格”：行情不必精确等于目标价，只要相邻 Tick 触达或跨过目标价，无论方向都属于一次穿越。

### Problem

方向式配置增加创建成本，也不符合图上放置一条目标价格线的交互。实时行情可能从目标价一侧直接跳到另一侧，使用精确相等会漏报。频繁来回穿越又可能造成通知轰炸。

### Options

1. 保留高于/低于两个方向。
2. 必须 Tick 精确等于目标价。
3. 双向穿越判断，并使用全局 cooldown 抑制短时间重复通知。

### Recommendation

选择 Option 3：

- 触达或从任一方向跨过目标价立即触发。
- 全局 cooldown 固定选项为 1、5、15、30、60 分钟，默认 5 分钟。
- 冷却期间继续更新价格所在侧但不通知；冷却结束后仍需一次新的穿越。
- 新建提醒或修改目标价后，首 Tick 只建立基线。
- 旧方向提醒保留 ID、名称、目标价、标的和启用状态，迁移后丢弃方向。

### Consequences

- 图表提醒线和提醒模型更简单，所有 timeframe 共享同一 instrument 提醒。
- Evaluator 必须保存每条提醒的上一有效侧、最后通知时间，并注入 Clock 以便测试。
- 迁移需要新 schema/version 和回归测试，不能直接删除旧字段而丢失用户配置。
- 冷却是通知抑制而不是停止策略观察。

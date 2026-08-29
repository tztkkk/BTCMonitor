# Current Development Plan

## 1. 当前阶段目标

本阶段只交付两项用户价值：

1. 可拖动、缩放、查看历史与 OHLC 的交互式 K 线。
2. 可在图上创建和拖动的双向穿越到价提醒。

执行顺序是：

```text
Planner 已完成通用边界确认
（Market / Alert / Chart）
        ↓
修复边界迁移前的已知行情正确性问题
        ↓
用小 TASK 落地可复用契约
        ↓
图表库独立可行性验证
        ↓
交互式 K 线与历史分页
        ↓
到价提醒迁移、冷却、图上创建与拖动
        ↓
Android 16 端到端验证
```

Signal、Indicator、复杂 Strategy、SMC、完整 Navigation 和全面 UI 重构不属于本阶段实施范围，统一放在文末候选区。

这里的“先整理架构”不等于先进行大规模代码重构：依赖方向和职责已在 `ARCHITECTURE.md`、`AGENTS.md`、ADR-006/007 中确认；Developer 首先关闭会影响多标的提醒正确性的 TASK-003，再分别落地 Market、Alert、Chart 最小契约。

## 2. Task Rules

状态只使用：

```text
TODO | IN_PROGRESS | REVIEW | DONE | BLOCKED | REFACTOR
```

优先级：`P0` 为核心正确性；`P1` 为当前阶段必须完成；`P2` 为体验增强；`P3` 为候选优化。

- `Parallel Safe`：核心文件与职责边界不重叠时可并行。
- `Cannot Run In Parallel`：会修改同一 ViewModel、Repository、Service、Screen、Gradle 或 schema，必须串行。
- 每个 Developer 一次只领取一个 TASK。
- 每个 TASK 完成后更新状态、测试结果和 Notes，不得顺便开始下一 TASK。

## 3. Existing Baseline

以下能力已经存在，Developer 必须复用，不得重新创建：

| Capability | Maturity | Existing implementation |
| --- | --- | --- |
| Android 16 工程与真机运行 | DONE | API 36、Compose、FGS、通知权限、真机测试文档 |
| 多标的行情 | PROTOTYPE | 单 OKX WebSocket、多 symbol 动态订阅、REST 快照 |
| K 线 | PROTOTYPE | 六周期 REST 数据、Compose Canvas 静态绘制 |
| 多价格提醒 | PROTOTYPE | DataStore CRUD、方向式阈值、边沿触发、通知 |
| 后台监控 | PROTOTYPE | specialUse FGS、START_STICKY、网络切换与重连 |
| 日志与更新 | DONE | 诊断日志、导出、GitHub Release 安全更新 |

## 4. Execution Queue

### TASK-003

**Title:** 修复多标的行情按 symbol 派发正确性  
**Status:** DONE  
**Priority:** P0  
**Goal:** 防止单一全局 1 秒节流让一个 symbol 的消息压制其他 symbol，确保所有标的都能进入 UI 更新和提醒评估。  
**Scope:** 将 `MarketDataManager.lastDispatchMillis` 改为按 symbol 的节流状态或等价公平机制；订阅移除/停止时清理状态；提取可单元测试的派发判断；增加多 symbol 同秒消息测试。  
**Out of Scope:** MarketRepository、图表功能、改变默认 1 秒采样间隔、更换 WebSocket。  
**Acceptance Criteria:** BTC 与 ETH 在同一秒内到达时都可派发；同一 symbol 仍按 1 秒限制；取消后重新订阅不继承脏状态；现有心跳/重连行为不变；相关单元测试和 `assembleDebug` 通过；Android 16 上两个标的的近价提醒可分别触发。  
**Dependencies:** Existing v0.3.0 baseline.  
**Affected Modules:** `market/MarketDataManager.kt`, market unit tests；必要时少量 test seam。  
**Notes:** **Cannot Run In Parallel** with TASK-004 or其他 MarketDataManager/Service 工作。这是开始架构迁移前必须关闭的正确性风险。按 symbol 节流、订阅/停止清理和单元测试已实现。自动化验收发现并经用户明确授权修复现有 `StrategyEngine` 跨 symbol Tick 错误清理其他提醒边沿状态的问题；新增跨 symbol 回归测试。2026-08-28 在 Xiaomi 25102RKBEC / Android 16（API 36）完成两项 instrumentation：真实 OKX BTC-USDT + ETH-USDT 双 symbol 均收到有效 Tick；可控跨 symbol Tick 分别触发 BTC/ETH 并发布两条独立 Android 通知。Reviewer 复核未发现 Critical/Major 问题；完整 `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest` 和真机 instrumentation 均通过。

### TASK-004

**Title:** 建立支持历史分页的 MarketRepository 最小契约  
**Status:** DONE  
**Priority:** P1  
**Goal:** 让图表和提醒上层依赖稳定的市场数据接口，而不是直接依赖 OKX、OkHttp 或 `CandleRepository` 具体类。  
**Scope:** 定义 instrument/symbol、timeframe、quote、candle page、connection/freshness/error 的最小模型和接口；历史查询支持 `before` 或等价游标、`hasMore`、排序和去重契约；提供 fake repository 与契约测试；记录生产迁移点。  
**Out of Scope:** 迁移全部生产调用方、改变 WebSocket owner、缓存数据库、增加数据源、修改 UI。  
**Acceptance Criteria:** API 不暴露 OKX JSON、OkHttp 或 Android UI 类型；能表达实时 quote 和向前分页 candle；分页结果顺序、重复项、取消和错误语义明确；fake 能验证 ViewModel/Chart 上层；无未经批准依赖；Build 通过。  
**Dependencies:** TASK-003.  
**Affected Modules:** 新 `domain/market`/`data/market` 边界、tests、`ARCHITECTURE.md`（仅实际偏差）。  
**Notes:** 完成前不迁移生产代码。与 TASK-005、TASK-006 **Parallel Safe**，但需要 Planner 预先分配不同文件。已新增 provider-neutral 的 instrument/symbol、timeframe、quote、realtime connection/freshness/error、排他 `before` 历史分页模型与 `MarketRepository` 接口；分页统一升序、按开盘时间去重并提供 `hasMore`/下一游标。测试 fake 支持实时状态、快照、订阅集、历史分页、结构化失败与可取消请求，契约测试已覆盖双页无重叠、排序去重、状态/错误和取消传播。生产调用方未迁移，迁移点已记录到 `ARCHITECTURE.md`。Reviewer 复核未发现 Critical/Major 问题。

### TASK-005

**Title:** 建立双向穿越到价提醒 Domain  
**Status:** DONE  
**Priority:** P1  
**Goal:** 把提醒判断从“高于/低于”改造成可复用、可确定性测试的双向目标价穿越能力。  
**Scope:** 定义不含 direction 的 TargetPriceAlert/等价领域模型；实现纯 evaluator，识别向上、向下、精确触达和跳价穿越；支持注入 Clock；实现全局 cooldown 语义；冷却期继续更新所在侧；新建/改价首 Tick 只建基线；编写迁移设计但暂不改 DataStore。  
**Out of Scope:** DataStore 正式迁移、图表 UI、通知重构、Signal 模型、每条提醒独立 cooldown。  
**Acceptance Criteria:** 测试覆盖向上/向下/精确等于/跳过目标价；默认 5 分钟以及 1/5/15/30/60 分钟合法值；冷却内不通知但更新侧；冷却结束后必须新穿越；新建/修改不立即通知；不同 alert 状态独立；Domain 不依赖 Compose、Android、OKX 或 Notification。  
**Dependencies:** TASK-003.  
**Affected Modules:** 新/调整 `domain/alert`, `strategy`, unit tests；ADR-007 仅在实现偏差时更新。  
**Notes:** 与 TASK-004、TASK-006 **Parallel Safe**；不得同时修改 `SettingsRepository`，正式迁移留给 TASK-011。已新增不含 direction 的 `TargetPriceAlert`、固定 1/5/15/30/60 分钟且默认 5 分钟的全局 cooldown 模型，以及注入 `Clock` 的纯 Domain evaluator。测试覆盖向上/向下跳价、精确触达、首 Tick 基线、冷却抑制但持续更新侧、冷却后必须新穿越、改价/重新启用重建基线、多 alert/多 symbol 独立状态和单 alert reset。未修改 DataStore、现有 `StrategyEngine`、Service、通知或 UI；TASK-011 的幂等迁移与生产切换设计已记录到 `ARCHITECTURE.md`。Reviewer 复核未发现 Critical/Major 问题。

### TASK-006

**Title:** 定义可复用交互式 K 线组件 API 与视口模型  
**Status:** DONE  
**Priority:** P1  
**Goal:** 在选择渲染技术前固定图表与页面之间的输入、状态和事件边界。  
**Scope:** 定义 ChartCandle/适配规则、ChartViewport、CrosshairState、AlertLine presentation model；定义 `onLoadOlder`、`onCreateAlert`、`onMoveAlert`、`onViewportChanged` 等事件；定义手势命中优先级和视口锚点规则；用 reducer/纯函数测试关键状态变化。  
**Out of Scope:** 选择图表库、绘制生产图表、网络请求、DataStore、ViewModel 迁移。  
**Acceptance Criteria:** API 不依赖 MonitorViewModel、OKX、DataStore、AlertConfig 或具体图表库；可表达加载更多、平移、时间缩放、价格缩放、十字光标和提醒线；拖线只在手势结束输出一次保存事件；历史数据插入后能通过稳定 anchor 保持视口；测试通过。  
**Dependencies:** TASK-003.  
**Affected Modules:** 新 `ui/chart` API/state/models 与 unit tests，`UI_SPEC.md`（仅实际偏差）。  
**Notes:** 与 TASK-004、TASK-005 **Parallel Safe**；TASK-007 必须基于此 API 做验证。已新增独立 `ui/chart` 纯模型与 reducer：`ChartCandle` 适配统一按开盘时间升序和去重；`ChartViewport` 使用 candle 时间 + 屏幕比例稳定锚点，支持平移、时间缩放、价格范围缩放和回到最新；Crosshair、AlertLine、历史加载状态和提醒线候选拖动均不依赖 ViewModel、OKX、DataStore、`AlertConfig` 或具体图表库。输出事件/Callback 覆盖 `onLoadOlder`、`onCreateAlert`、`onMoveAlert`、`onViewportChanged`；提醒线移动只在结束 action 输出一次保存事件。测试覆盖适配、历史前插锚点、手势命中优先级、加载去重、双轴视口变化、十字光标创建提醒和拖线单次提交，未修改生产图表或 UI_SPEC。Reviewer 复核未发现 Critical/Major 问题。

### TASK-007

**Title:** 验证交互式 K 线图表库并完成选型 ADR  
**Status:** DONE  
**Priority:** P1  
**Goal:** 用证据决定采用成熟开源库还是可复用 Compose Canvas，不直接替换正式详情页。  
**Scope:** 搜索并筛选仍维护的 Android/Compose 候选；核验官方仓库、发布记录、许可证和 Android 16/Compose 兼容性；建立独立实验页面/最小 Demo，用真实 candle 适配数据验证左右历史、双指时间缩放、价格轴缩放、十字光标/OHLC、自定义提醒线、提醒线拖动和分页插入后的视口稳定；比较性能与扩展成本；更新 ADR-006。  
**Out of Scope:** 正式页面接入、提醒持久化、重做业务 UI、为验证而修改生产 WebSocket。  
**Acceptance Criteria:** 至少记录候选和淘汰原因；选中方案有可运行 Demo 和全部核心能力证据；库方案记录准确版本与许可证且通过 Android 16；若没有合适库，ADR 明确选择 Compose Canvas；实验代码与生产入口隔离；Build 通过。  
**Dependencies:** TASK-004, TASK-006.  
**Affected Modules:** 独立 chart spike/demo、Gradle（仅验证所需且经 TASK 授权）、`DECISIONS.md`.  
**Notes:** **Cannot Run In Parallel** with其他 Gradle 或 chart renderer 工作。验证结束前不得开始正式渲染实现。2026-08-29 已核验 Vico 3.3.1、TradingView Lightweight Charts Android 5.2.0、MPAndroidChart 3.1.0、chartkit 0.1.8 和 MPAndroidChart-Compose 候选；没有库同时满足价格轴缩放、稳定历史前插、持久 Crosshair/OHLC 与可命中拖动提醒线。ADR-006 已接受可复用 Compose Canvas，未增加第三方依赖。新增 Debug-only `ChartTechnologySpikeActivity`，默认通过现有 `CandleRepository` 加载真实 BTC-USDT Candle，并提供仅供 instrumentation 使用的确定性 fixture；以 TASK-006 API 验证核心交互，未接入正式页面。6 项 Spike 几何/视口测试通过。2026-08-29 在 Xiaomi 25102RKBEC（Android 16 / API 36）验证真实 K 线加载与基础渲染无 Crash；标准 `connectedDebugAndroidTest` 通过确定性数据验证水平平移、双指时间缩放、价格轴缩放、十字光标/OHLC、提醒线拖动单次提交及历史前插。`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 均通过；Reviewer 复核未发现 Critical/Major 问题。未开始 TASK-008。

### TASK-008

**Title:** 建立可复用交互式 K 线渲染基础  
**Status:** DONE  
**Priority:** P1  
**Goal:** 按 ADR-006 的选型实现生产可用的图表外壳，同时保持现有详情页功能可回退。  
**Scope:** 用选定库的 adapter 或 Compose Canvas 实现 Candle 绘制、坐标换算、Viewport 应用、提醒线绘制和基本状态；只依赖 TASK-006 API；提供 Loading/Empty/Error；保留现有静态图表直到新组件验收。  
**Out of Scope:** 完整手势、历史分页接入、图上创建/拖动保存、删除旧图表。  
**Acceptance Criteria:** 给定相同输入可在 Preview/test host 重用；不读取 ViewModel/Repository；不同 timeframe 可重建正确视口；提醒线跨 timeframe 显示；旧图表仍可回退；Android 16 基础渲染无 Crash；Build 通过。  
**Dependencies:** TASK-007.  
**Affected Modules:** `ui/chart` renderer/adapter, asset detail integration behind isolated switch/branch.  
**Notes:** **Cannot Run In Parallel** with TASK-009、TASK-010、TASK-012、TASK-013 或其他详情图表工作。2026-08-29 已按 ADR-006 新增只依赖 TASK-006 API 的可复用 Compose Canvas 渲染器，支持 Candle、Viewport、价格/坐标换算、启用/停用及图外提醒线、Loading/Empty/Error、文字摘要和 Preview；Debug 详情页通过隔离分支启用，Release 继续保留原静态图表回退。新增渲染计划/坐标单元测试和确定性 Debug test host。Xiaomi 25102RKBEC（Android 16 / API 36）专用 instrumentation 验证 60 根可见 K 线和 3 条不同状态提醒线基础渲染无 Crash；`testDebugUnitTest`、`testReleaseUnitTest`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 均通过。未实现 TASK-009 及后续手势、分页或提醒保存。

### TASK-009

**Title:** 实现图表平移、双轴缩放与十字光标 OHLC  
**Status:** DONE  
**Priority:** P1  
**Goal:** 完成用户直接浏览和检查 K 线的核心交互。  
**Scope:** 单指左右平移；双指时间范围缩放；价格轴纵向缩放；长按出现十字光标、按住移动、松手保留、点击空白关闭；选择最近 Candle 并显示本地时间与 OHLC；处理提醒线/十字光标/平移/缩放手势优先级；提供“回到最新”。  
**Out of Scope:** 自动加载更早数据、图上创建提醒、提醒线保存、Volume/Indicator。  
**Acceptance Criteria:** 四类手势符合 UI_SPEC；缩放不改变源 Candle；十字光标命中 Candle 正确且边界不溢出；松手保持、空白关闭；字体放大仍可读；大量当前数据下交互无明显卡顿；相关状态/坐标测试、Build 和 Android 16 手势验证通过。  
**Dependencies:** TASK-008.  
**Affected Modules:** `ui/chart`, Asset Detail 少量接入, tests.  
**Notes:** **Cannot Run In Parallel** with任何 chart renderer/gesture 或 `MonitorScreen.kt`/详情 Screen 工作。2026-08-29 已在 TASK-008 可复用 Canvas 组件中实现单指水平平移、双指时间范围缩放、右侧价格轴纵向缩放、长按/移动/松手固定十字光标、空白点击隐藏和“回到最新”；十字光标按最近 Candle 显示本地时间及 O/H/L/C，切换 symbol/timeframe 会重建交互状态，所有手势仅更新 Chart state 并通过既有 callback 边界输出，不直接访问 Repository/DataStore。新增平移/双轴缩放边界、源 Candle 不变、十字光标固定/隐藏、回到最新及 10,000 根数据可见窗口上限测试。Xiaomi 25102RKBEC（Android 16 / API 36）在 1.3× Compose 字体密度下通过生产 renderer 手势 instrumentation，并与 TASK-008 基础渲染用例联合通过；`testDebugUnitTest`、`testReleaseUnitTest`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 均通过。未实现 TASK-010 自动历史分页、TASK-012 图上创建提醒或 TASK-013 提醒线拖动保存。

### TASK-010

**Title:** 接入 K 线历史自动分页并保持视口  
**Status:** TODO  
**Priority:** P1  
**Goal:** 用户拖到最左端时自动加载更早 K 线，追加后视图不跳回最新位置。  
**Scope:** 将现有 REST candle 实现适配 TASK-004 的分页接口；ViewModel/UiState 支持初次加载、load older、hasMore、分页错误和并发去重；图表达到左边阈值触发一次 `onLoadOlder`；合并时按时间去重排序并恢复 anchor。  
**Out of Scope:** 本地历史数据库、后台预加载、无限制内存增长策略优化、其他数据源。  
**Acceptance Criteria:** 左边界自动加载；同一时刻最多一个旧数据请求；重复 Candle 不重复显示；加载成功后屏幕锚点保持；失败可局部重试且现有数据保留；切换 symbol/timeframe 取消旧请求且不串数据；repository/ViewModel 测试、Build 和 Android 16 验证通过。  
**Dependencies:** TASK-004, TASK-009.  
**Affected Modules:** `market/CandleRepository` adapter, MarketRepository implementation, `MonitorViewModel`/新 chart ViewModel, chart UiState, tests.  
**Notes:** **Cannot Run In Parallel** with MarketRepository、ViewModel 或 chart viewport 工作。

### TASK-011

**Title:** 迁移旧方向提醒并持久化全局冷却设置  
**Status:** TODO  
**Priority:** P1  
**Goal:** 在不丢失用户配置的前提下，把现有提醒正式迁移为双向到价提醒，并保存统一 cooldown。  
**Scope:** 建立新提醒 schema/version；迁移旧 `AlertConfig`，保留 ID、name、assetId、symbol、enabled、threshold，移除 direction；DataStore 增加全局 cooldown 固定值，默认 5 分钟；生产 Service/Strategy 接入 TASK-005 evaluator；保留现有提醒 CRUD 能力。  
**Out of Scope:** 图上操作、Signal 历史、Room、每条提醒 cooldown、通知 UI 重设计。  
**Acceptance Criteria:** 旧 JSON 样例迁移不丢字段、不重复；已迁移数据可 round-trip；非法 cooldown 回退 5 分钟；Service 使用双向穿越并遵守冷却；冷却期继续跟踪所在侧；升级后现有提醒列表可用；单元测试、Build 和 Android 16 升级冒烟通过。  
**Dependencies:** TASK-005.  
**Affected Modules:** `model`, `settings/SettingsRepository`, `strategy`, `service`, tests.  
**Notes:** 与纯 chart TASK 可 **Parallel Safe**，但 **Cannot Run In Parallel** with任何 SettingsRepository/StrategyEngine/Service/schema 工作。

### TASK-012

**Title:** 在十字光标位置创建到价提醒  
**Status:** TODO  
**Priority:** P1  
**Goal:** 用户无需手工判断方向，可直接从图表选定价格并创建提醒。  
**Scope:** 十字光标旁显示“＋”，靠近右边界时自动翻转位置；点击打开轻量确认面板；预填目标价、要求提醒名称；保存后通过上层 callback/repository 创建提醒；新提醒线立即显示但首 Tick 只建基线；所有 timeframe 显示。  
**Out of Scope:** 拖动已有提醒线、在图上编辑名称/启停/删除、改变提醒列表职责。  
**Acceptance Criteria:** “＋”只在有效十字光标时可用且不超出屏幕；名称和正数价格校验；取消不写入；保存只提交一次；成功/失败反馈明确；创建后不立即通知；切换所有周期仍显示；提醒列表仍能编辑/启停/删除；Build 和 Android 16 验证通过。  
**Dependencies:** TASK-008, TASK-009, TASK-011.  
**Affected Modules:** `ui/chart`, Asset Detail/Alert editor, ViewModel callback, settings repository 调用。  
**Notes:** **Cannot Run In Parallel** with TASK-013、TASK-014 或详情/提醒 UI 工作。

### TASK-013

**Title:** 拖动提醒线并在松手时立即保存  
**Status:** TODO  
**Priority:** P1  
**Goal:** 用户可在图表上直观调整已有到价提醒。  
**Scope:** 提醒线命中测试；垂直拖动时实时显示候选价格；手势结束只提交一次；成功即时保留并反馈；失败恢复原价格并提供错误反馈；改价后重置该提醒基线；禁用提醒线不可误保存。  
**Out of Scope:** 点击提醒线打开管理面板、图上重命名/启停/删除、撤销历史、多选拖动。  
**Acceptance Criteria:** 提醒线手势优先于普通平移且不破坏缩放/十字光标；拖动过程不连续写 DataStore；松手立即保存；失败可靠回滚；只重置被修改提醒的基线；修改后必须新穿越才通知；所有 timeframe 显示新价格；Build 和 Android 16 验证通过。  
**Dependencies:** TASK-011, TASK-012.  
**Affected Modules:** `ui/chart`, Alert presentation model, Asset Detail/ViewModel, settings/strategy integration, tests.  
**Notes:** **Cannot Run In Parallel** with任何 chart gesture、提醒 UI、SettingsRepository 或 ViewModel 工作。

### TASK-014

**Title:** 在更多/监控设置中提供全局冷却选项  
**Status:** TODO  
**Priority:** P1  
**Goal:** 用户可以理解并选择所有到价提醒共用的重复提醒冷却时间。  
**Scope:** 在“更多”增加“监控设置”区；提供 1、5、15、30、60 分钟单选；读取/写入 TASK-011 设置；解释冷却期间不通知、结束后仍需新穿越；保存结果反馈。  
**Out of Scope:** 每条提醒单独设置、自由输入、重做整个更多页、通知频道配置。  
**Acceptance Criteria:** 默认显示 5 分钟；选择后立即持久化，重启仍保留；只有合法固定值；现有运行中的 evaluator 对后续判断使用新值；Loading/Disabled/Error 反馈合理；Build 与 Android 16 验证通过。  
**Dependencies:** TASK-011.  
**Affected Modules:** More/Monitoring Settings UI, `MonitorViewModel`, `SettingsRepository`.  
**Notes:** 可与 TASK-008–010 **Parallel Safe** only if现有 `MonitorScreen.kt` 已拆分且文件不重叠；否则 **Cannot Run In Parallel**。不可与 TASK-012/013 并行。

### TASK-015

**Title:** 完成交互式 K 线与到价提醒端到端验收  
**Status:** TODO  
**Priority:** P1  
**Goal:** 确认新架构和用户流程在 Android 16 真机上形成稳定闭环，才结束本阶段。  
**Scope:** 运行全部相关单元测试与 assembleDebug；执行多 symbol、六 timeframe、历史分页、平移/双轴缩放、十字光标/OHLC、图上创建、提醒线拖动、列表管理、冷却选项、前后台/锁屏/网络切换测试；检查迁移后的真实旧配置；Reviewer 按 Critical/Major/Minor 报告；修复只通过独立后续 fix TASK 进行。  
**Out of Scope:** 新功能、视觉重设计、Signal/Indicator/Strategy、自动化交易。  
**Acceptance Criteria:** 前置 TASK 全部 DONE；Build 与相关测试通过；Android 16 测试结果记录；无数据丢失、跨 symbol 串线、明显手势冲突或新增 Crash；Critical/Major 已关闭或明确接受；TODO 与必要 ADR 更新；阶段结论明确。  
**Dependencies:** TASK-003 through TASK-014.  
**Affected Modules:** 全阶段相关模块、`docs/ANDROID16_TESTING.md`, TODO/ADR。  
**Notes:** **Cannot Run In Parallel** with正在修改本阶段代码的任何 TASK；这是验收任务，不允许借机扩 Scope。

## 5. Dependency and Parallel Map

```text
TASK-003
   ├── TASK-004 ───────┐
   ├── TASK-005 ── TASK-011 ───────────────┐
   └── TASK-006 ── TASK-007 ── TASK-008 ── TASK-009 ── TASK-010
                                      │          │
                                      └──── TASK-012 ── TASK-013
                         TASK-011 ─────┘
                         TASK-011 ───────── TASK-014

TASK-003 ... TASK-014 ── TASK-015
```

允许的主要并行窗口：

- TASK-004、TASK-005、TASK-006 在 TASK-003 完成后可由不同 Developer 并行。
- TASK-011 可与纯图表 TASK-008–010 并行，但只能在文件已拆分、不会同时修改 `MonitorScreen.kt`/同一 ViewModel 时进行。
- TASK-012、TASK-013 必须串行；TASK-015 必须最后执行。

## 6. Later Candidates — Not Current Tasks

以下仅保留方向，不允许 Developer 直接领取；本阶段完成后由 Planner 重新问答和拆分：

- Design System 与 Screen/ViewModel 拆分。
- Signal 模型、历史存储、Signal Center 和通知 deep link。
- EMA、RSI、MACD、ATR、Volume 等 Indicator。
- EMA/Breakout/Price Action/SMC Strategy。
- GOLD、NASDAQ、S&P 500、股票数据源。
- 五目的地 Bottom Navigation。
- 后台中断恢复产品化、开机恢复评估。
- 回测、组合规则、云同步和自动交易（后两者当前仍明确不做）。

禁止把候选方向重新展开为大量 TODO，除非用户确认它成为下一阶段目标。

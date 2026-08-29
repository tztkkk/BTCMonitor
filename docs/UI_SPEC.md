# Monitor UI Specification

## 1. Product UI Direction

Monitor 的界面定位是：**专业、简洁、高信息密度的金融行情监控 App**。

- Dark Theme 优先，适合长时间查看和弱光环境。
- 价格、涨跌、连接状态、规则状态和信号优先于装饰。
- 避免花哨渐变、大面积无意义装饰、过多动画和页面间不一致的设计语言。
- 重要信息不能只用颜色表达，应同时使用文本、图标或符号。
- UI 不作盈利暗示；信号是监控事件，不是交易建议。

## 2. Current UI

当前 v0.3.0 使用 Compose + Material 3，结构为：

```text
Top App Bar: Monitor + version
Primary Tabs:
├── 行情
│   ├── 监控状态
│   ├── 自选行情卡片
│   └── 标的详情（K线 + 价格提醒）
├── 日志
└── 更多
    ├── 通知测试
    ├── 行情获取测试
    ├── 版本更新
    └── GitHub 仓库设置
```

详情导航由 `selectedAssetId` 本地状态完成，不是 Navigation Compose。现有 Dark ColorScheme 定义在 `MonitorScreen.kt`，部分涨跌、图表和警报色硬编码在组件内。当前 UI 可用但属于 Prototype；本轮只记录，不重写。

## 3. Target Navigation

长期 Bottom Navigation：

```text
首页   行情   策略   信号   我的
```

目标信息架构：

- 首页：概览与操作入口。
- 行情：自选、搜索、分类和市场详情。
- 策略：规则/策略管理和运行状态。
- 信号：Signal 历史与详情。
- 我的：通知、后台、数据源、主题和 App 设置。

迁移约束：

- 当前 `行情/日志/更多` Tab 在明确 Navigation TASK 前保留。
- “日志”长期可归入“我的/诊断”，但迁移不得丢失现有能力。
- 通知 deep link 需在 Signal/Navigation 模型确定后实施。
- 导航改造不得与大规模 Screen 拆分在同一个 TASK 中完成。

## 4. Screen Specifications

### 4.1 首页

#### Market Overview

优先显示 BTC、ETH、GOLD、NASDAQ；只展示数据源实际支持且 freshness 可判断的品种。每项包含：

- Symbol / Name
- Price
- Change / Change %
- 简洁 Trend 表达
- Market status 或数据更新时间

#### Active Monitoring

- 运行中的规则数量
- Running / Paused / Error / Waiting for network
- 后台权限/通知权限异常入口
- 最近一次行情时间

#### Latest Signals

- 最近 Signal 的品种、类型、策略和时间
- Empty 状态明确说明尚无信号
- 点击进入 Signal Detail

#### Quick Alert

- 从首页快速选择品种、方向和价格
- 复用统一 Rule Editor 与校验，不创建另一套价格提醒逻辑

### 4.2 行情

列表字段：

```text
Symbol | Name | Price | Change | Change % | Market Status
```

交互：

- 自选增删
- 搜索
- Crypto / Commodity / Index / Stock 分类
- 点击进入行情详情
- 下拉/按钮刷新只用于按需快照，不创建额外常驻 WebSocket

列表卡片规范：Symbol 和 Price 为一级信息，Name、更新时间和提醒数为二级信息；涨跌值右对齐，数字使用等宽或 tabular number 能力（可用时）。

### 4.3 行情详情

顶部：

```text
BTC/USDT
Current Price
Absolute Change · Change %
Market Status / Updated Time
```

周期选择：

```text
1m  5m  15m  1H  4H  1D
```

主要区域：

- Candlestick Chart
- Volume
- Indicator overlays/panels
- Loading、retry 和 stale-data 标记

底部：

- 当前规则/策略状态
- Create Alert
- 查看相关 Signals

当前价、Alert 和未来 Signal 的图表线必须使用不同颜色/线型并提供图例。图外提醒需要明确“高于/低于当前图表范围”。

#### 4.3.1 Interactive chart gestures

| Gesture | Result |
| --- | --- |
| 单指左右拖动 | 平移时间轴，查看历史 K 线 |
| 拖到最左边界 | 自动请求更早一页；加载后保持视口锚点，不跳回最新 K 线 |
| 双指横向缩放 | 改变可见 K 线数量/时间范围 |
| 双指纵向或价格轴上下缩放 | 改变可见价格范围，不修改数据本身 |
| 长按 | 显示十字光标并选中最近 K 线 |
| 长按后拖动 | 移动十字光标，实时更新 Time、O、H、L、C |
| 松手 | 十字光标保持可见 |
| 点击图表空白处 | 关闭十字光标 |
| 拖动提醒线 | 调整提醒目标价格；松手立即保存 |

手势冲突优先级必须由图表组件统一处理：提醒线命中时拖动提醒线；长按进入十字光标；普通拖动平移；多指手势缩放。任何手势都不得直接调用 Repository 或 DataStore，而是输出事件回调。

十字光标信息区至少显示：本地格式化时间、Open、High、Low、Close。可额外显示 Volume，但不是本阶段验收前提。缩放/拖动后应保持明确的“回到最新”入口。

#### 4.3.2 Create target-price alert on chart

1. 用户长按并移动十字光标选择价格。
2. 十字光标右侧显示可点击的“＋”；应避免超出屏幕，可在靠近右边界时翻转到左侧。
3. 点击“＋”打开轻量确认面板。
4. 面板预填十字光标价格，要求填写提醒名称，并允许确认目标价格。
5. 保存后创建一条绑定当前 instrument 的双向穿越到价提醒，并显示水平提醒线。

到价提醒不绑定 timeframe，因此切换 `1m / 5m / 15m / 1H / 4H / 1D` 后仍显示。新建后第一条行情只建立基线，不因创建位置在当前价另一侧而立即通知。

#### 4.3.3 Edit target-price alert on chart

- 用户命中并垂直拖动提醒线时，线旁实时显示候选价格。
- 松手立即保存，不增加二次确认；成功后使用 Snackbar 或等价短反馈。
- 保存失败时提醒线恢复到原价格，并提供简短错误与重试入口。
- 点击提醒线不打开管理面板。
- 图表下方现有提醒列表保留，继续负责重命名、启用/停用和删除。
- 禁用提醒是否显示由组件输入决定；本阶段默认可显示为 Disabled 样式，但不可拖动触发误保存。

#### 4.3.4 Global alert cooldown setting

位置：`更多 → 监控设置`。

- 单选固定值：1、5、15、30、60 分钟。
- 默认：5 分钟。
- 所有到价提醒共用该值。
- 修改后立即持久化并对后续触发判断生效。
- UI 文案需说明：冷却期间不会重复通知；冷却结束后仍需发生新的价格穿越。

### 4.4 策略中心

策略卡片至少包含：

```text
Strategy
Instrument
Timeframe
Status
Last Trigger
```

状态：

- Running：正常接收行情并参与评估。
- Paused：用户暂停。
- Error：配置、数据或执行错误。
- Waiting：等待行情/warm-up 时可作为辅助状态，不能错误显示 Running。

卡片操作包括查看、暂停/恢复、编辑；删除必须二次确认。编辑器按 Rule 类型显示字段，复用统一校验与保存结果反馈。

### 4.5 信号中心

Signal Card 基础结构：

```text
BTC/USDT                         Time
1H · EMA Strategy
LONG SIGNAL
Trigger: 112,500
Strength: Strong
Status: New
```

- signalType、strength 和 status 必须有文本，不只靠颜色。
- 支持按品种、策略、时间和状态筛选是后续能力。
- 点击进入 Signal Detail，展示触发规则、触发数据、时间和通知状态。

### 4.6 我的

- Notification Settings：权限状态、系统频道入口、测试通知。
- Monitoring Settings：全局暂停、运行状态与已启用规则数。
- Data Source：当前来源、连接状态、诊断入口；未完成供应商选择前不可伪造切换。
- Battery / Background Status：通知权限、电池优化状态、Doze/厂商限制说明和真机诊断。
- Theme：当前 Dark，未来可支持 System/Light/Dark。
- About：版本、隐私、免责声明、更新和诊断日志。

## 5. Design System

### 5.1 Color Roles

首个 Design System TASK 应把颜色移出 Screen 文件，集中到 theme/tokens。建议基于当前视觉保持以下角色；十六进制值是目标初始值，可在视觉 QA 后通过 ADR/TASK 小幅调整。

| Role | Value | Usage |
| --- | --- | --- |
| Background | `#0B1220` | 页面背景 |
| Surface | `#151E2E` | 普通容器 |
| Card | `#182337` | 卡片，与页面背景形成轻微层次 |
| Surface Variant | `#223047` | 编辑器、嵌套卡片、选中背景 |
| Primary | `#63B3ED` | 主要动作、当前价、选中状态 |
| Primary Text | `#F3F7FC` | 一级文本 |
| Secondary Text | `#A7B3C5` | 描述、时间、辅助状态 |
| Divider | `#2C3A50` | 分隔线和图表网格 |
| Positive | `#39D98A` | 上涨/正向；须同时有 `+` 或文字 |
| Negative | `#FF5C5C` | 下跌/错误；须同时有 `-` 或文字 |
| Warning | `#F6AD55` | 风险、提醒价、需要注意 |
| Signal | `#A78BFA` | 一般 Signal 强调；多空仍用文本区分 |
| Disabled | `#667085` | 禁用内容/控件 |

约束：

- 业务代码不得直接新增 `Color(0x...)`。Design System 建立后统一引用 semantic color token。
- Positive/Negative 不等于 Buy/Sell；Signal 类型必须由文字或图标明确。
- 对比度以可读性为准，正文与背景目标满足 WCAG AA。

### 5.2 Typography

使用 Material 3 typography 语义，不在单个 Screen 随意创建字号：

| Token | Suggested spec | Usage |
| --- | --- | --- |
| Display Price | 32sp / Bold | 详情当前价格 |
| Headline | 24sp / Bold | 页面或关键区标题 |
| Title Large | 20sp / Semibold | Symbol、主卡片标题 |
| Title Medium | 16sp / Semibold | 区块和策略名 |
| Body | 14sp / Normal | 正文与表单 |
| Body Small | 12sp / Normal | 辅助说明、更新时间 |
| Label | 12sp / Medium | 状态、按钮、标签 |

价格和时间优先使用 tabular figures；若默认字体无法保证，可在 Design System TASK 中评估而不擅自引入字体依赖。

### 5.3 Spacing

以 4dp 为基础网格：

| Token | Value | Usage |
| --- | --- | --- |
| `space-xs` | 4dp | 图标/紧密标签间隔 |
| `space-sm` | 8dp | 行内、卡片内部小间距 |
| `space-md` | 12dp | 列表项和紧凑卡片 |
| `space-lg` | 16dp | 页面边距、标准卡片 padding |
| `space-xl` | 24dp | 区块间距 |
| `space-2xl` | 32dp | 空状态和大区块 |

页面横向 padding 默认 16dp；高密度图表页允许 12dp，但同一层级需一致。

### 5.4 Shape

- Small control radius：8dp。
- Card/button radius：12dp。
- Dialog/large sheet radius：20dp。
- 金融数据卡片避免过度圆润和胶囊化；Chip/Status 可使用 full radius。

### 5.5 Cards

- 标准 Card 使用 Card color、12dp radius、16dp padding、无强阴影或极低 elevation。
- Nested Card 使用 Surface Variant，padding 12dp。
- Section Card 标题使用 Title Medium/Large，正文按 8–12dp 垂直节奏。
- 整卡可点击时提供明确按压反馈；内部危险操作与整卡导航不可冲突。

### 5.6 Buttons

- Primary Button：每屏一个主要提交/创建动作。
- Outlined Button：刷新、取消、次要操作。
- Text Button：低权重操作；删除使用 Negative 文本并二次确认。
- 最小触控区域 48dp；Disabled 状态降低强调同时保持文字可读。
- 网络提交时显示进行中状态并阻止重复触发。

### 5.7 Icons

- 使用统一 Material icon 语言；不要用不同风格 emoji 替代功能图标。
- 图标默认 20–24dp，最小触控区域 48dp。
- 纯图标按钮必须提供 contentDescription。
- 状态图标必须配文字或可访问描述。

## 6. Common States

### Loading

- 首次页面加载：在内容位置显示 progress/skeleton，不用全屏永久遮罩。
- 刷新：保留旧数据并标记 refreshing；若数据已过期，显示 stale 状态。
- 按钮提交：禁用重复提交并显示进行中。

### Empty

空状态必须说明“为什么为空”和“下一步是什么”。例如：

- 无自选：`还没有关注的市场` + `添加标的`。
- 无规则：`该标的暂无监控规则` + `创建提醒`。
- 无信号：`尚未产生信号`，不得暗示系统故障。

### Error

- 错误靠近失败内容显示，包含简短原因和 `重试`。
- K 线失败不得显示成后台监控失败；连接、行情、策略、通知错误分开表达。
- 技术细节进入诊断日志，UI 不直接展示长堆栈。

### Disabled / Paused

- Disabled rule 显示 `已停用`。
- 全局暂停显示 `全部监控已暂停`，与断网/Error 区分。
- 禁用控件必须说明前置条件，例如通知权限或无数据。

### Feedback

- 保存/删除结果使用可消失的 Snackbar 或等价反馈；当前页面顶部常驻 message 是 Prototype。
- 删除标的同时删除提醒属于破坏性操作，必须列出影响并二次确认。
- 拖动提醒线的即时保存需要成功/失败反馈，但连续拖动过程中不得连续写入；只在手势结束时提交一次。

## 7. Financial Data Formatting

- Symbol 统一 `BASE/QUOTE` 用于展示；数据层原始 `BTC-USDT` 可保留，展示转换必须集中。
- 价格精度由 instrument metadata 或集中 formatter 决定，不在各组件复制判断。
- 涨跌幅固定带正负号，例如 `+2.31%`、`-1.08%`。
- 未知数据使用 `—`，不要用 `0` 伪装有效行情。
- 时间至少区分交易所时间、接收时间和本地显示时区；当前 UI 显示本地时间。
- stale threshold 确定后，过期价格需显示时间和警告，不继续呈现为实时。

## 8. Accessibility and Compatibility

- 所有触控目标至少 48×48dp。
- 支持系统字体缩放；关键数据不得在 1.3× 字体下重叠或截断。
- 颜色不是唯一状态信号。
- TalkBack 顺序应先 Symbol/Price/Change，再操作。
- 图表需提供文本摘要（周期、最新 OHLC、提醒价），Canvas 不能成为唯一信息来源。
- 以 Android 16 真机深色模式、通知权限拒绝/允许、前后台和锁屏为主要验证环境。

## 9. UI Definition of Done

UI TASK 除通用 Definition of Done 外还需：

- 使用 Design System token，不新增散落颜色/尺寸。
- 覆盖适用的 Loading、Empty、Error、Disabled 状态。
- 不把网络或策略逻辑放入 Composable。
- 在小屏和字体放大场景没有明显裁切。
- 关键操作有明确反馈，破坏性操作有确认。
- Compose preview 或截图/真机验证覆盖主要状态；如项目暂无截图测试，应在 TASK Notes 说明。

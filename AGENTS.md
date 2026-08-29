# Project

本项目是 Android 金融行情与交易策略监控 App。当前产品名为 `Monitor`，当前实现以 OKX 公开现货行情和本地价格提醒为主，不包含交易下单、账号、API Key 或云端服务。

核心链路：

```text
Market Data
    ↓
Indicator / Feature
    ↓
Rule / Strategy
    ↓
Signal
    ↓
Notification
```

项目已能在 Android 16 真机安装运行。不得重新创建工程或把“验证 Android 能否运行”当作新任务。

# Target

- 当前只开发 Android。
- 主要测试环境：Android 16 真机。
- `minSdk = 36`
- `targetSdk = 36`
- `compileSdk = 36`
- 以上是当前真实配置；修改 SDK 边界必须由明确 TASK 和 ADR 驱动。

# Technology Stack

- 单模块 Android Application：`:app`
- Kotlin `2.2.21`
- Android Gradle Plugin `8.13.2`
- Gradle Wrapper `8.13`
- Java/JVM target `17`
- Jetpack Compose + Material 3，Compose BOM `2026.06.01`
- AndroidX Core、Activity Compose、Lifecycle Runtime、Lifecycle Compose、ViewModel Compose
- Kotlin Coroutines（由 AndroidX/Compose 依赖链提供并在源码中使用）
- OkHttp `5.4.0`：公开 REST 与 WebSocket
- Preferences DataStore `1.2.1`：自选、提醒、暂停状态和 GitHub 仓库设置
- `org.json`：行情与本地 JSON 数据解析
- JUnit 4；AndroidX Test、Espresso、Compose UI Test 依赖已配置
- 手工 application-scoped `AppContainer`，未使用依赖注入框架
- 当前没有 Room、Retrofit、Navigation Compose、WorkManager

# Source of Truth

1. 当前源码和 Gradle/Manifest 配置是实现事实的唯一来源。
2. `docs/PRD.md` 定义产品范围，`docs/ARCHITECTURE.md` 区分当前架构与目标架构。
3. `docs/TODO.md` 是 Developer 的工作入口；TASK 不得覆盖本文件、PRD 或已接受 ADR。
4. README、旧测试记录或注释与代码冲突时，先核验代码，再修正文档；不要按文档猜测实现。

# Architecture Rules

在不进行无关大改的前提下，演进方向为：

```text
Compose UI
    ↓
ViewModel / UI State
    ↓
Domain / Rule / Strategy
    ↓
Repository
    ↓
Remote / Local DataSource
```

实时行情目标链路：

```text
Market API / WebSocket
    ↓
MarketDataSource
    ↓
MarketRepository
    ↓
Market State
    ├── ViewModel / UI
    └── Strategy Engine
```

策略与提醒必须保持：

```text
Market Data → Indicator → Strategy → Signal
Signal → Notification Manager → Android Notification
```

当前代码还未完整实现所有目标层。演进必须由小 TASK 驱动，优先复用 `MarketDataManager`、`CandleRepository`、`StrategyEngine`、`NotificationHelper`、`SettingsRepository` 和 `MonitorStateStore`，不得为了匹配目标图一次性重写。

明确禁止：

- Compose 页面直接创建或维护 WebSocket。
- UI 直接负责指标或策略计算。
- Strategy 直接操作 Compose/UI。
- Strategy 自己发送 Android Notification。
- 多个页面或模块无必要重复建立相同行情连接。
- 多个模块重复实现同一个指标。
- 把全部业务逻辑继续堆入单个 ViewModel 或单个 Screen 文件。
- 为完成一个 TASK 大规模修改无关代码。
- 在没有 TASK/ADR 的情况下引入新的框架或抽象层。
- 用删除已有功能、测试或错误处理的方式让 Build 通过。

当前交互式 K 线演进还必须遵守：

- 图表组件不得直接依赖 `MonitorViewModel`、OKX 协议类、DataStore 或 Android Notification。
- 图表只接收可复用的 Candle、Viewport、Marker/AlertLine 等输入，并通过事件回调请求加载历史、创建或移动提醒。
- 手势、视口和价格坐标换算不得与具体行情供应商耦合。
- 到价穿越判断与冷却规则属于 Domain，不得在 Canvas/图表回调或 Compose 页面内计算。
- 引入图表库前必须完成独立可行性验证和 ADR；若没有合适库，允许继续使用可复用的 Compose Canvas 方案。

# Android Background Rules

- 保留当前用户可见的 `specialUse` Foreground Service 方案，除非 ADR 明确替代。
- 不得声称 Foreground Service 能绕过 Doze、强行停止、厂商电池限制或保证永久在线。
- Service 负责编排生命周期；连接、策略判断和通知发送保持独立职责。
- WebSocket 必须只有明确 owner，连接、重连、心跳、网络切换和取消必须可追踪。
- 涉及 Service、通知权限、后台启动、重启恢复或电池策略的 TASK，必须给出 Android 16 真机测试项。
- WorkManager、BOOT receiver、WakeLock、忽略电池优化或精确闹钟都不是默认方案；采用前必须有明确需求和 ADR。

# Development Workflow

```text
Requirement → Planner → TASK → Developer → Reviewer → Real-device Test → Done
```

## Planner

- 维护 PRD、Architecture、UI Spec、TASK、Acceptance Criteria 和 ADR。
- 默认不直接实现业务代码。
- 标记 TASK 是否 `Parallel Safe` 或 `Cannot Run In Parallel`。
- 两个 TASK 若会大量修改同一个 ViewModel、Repository、Service、Navigation 或 Gradle 文件，不得并行。

## Developer

- 读取 TASK，先搜索现有实现，再做最小范围修改。
- 完成 Build、相关测试和 TODO 状态更新。
- 不擅自扩大 Scope、改变需求或总体架构。

## Reviewer

- 检查 Requirement、Architecture、Lifecycle、Concurrency、Network、Error Handling、Performance、Duplicate Code、Android Compatibility 和 Scope Creep。
- 默认先报告问题，不直接大规模重写。
- 问题等级统一为 `Critical`、`Major`、`Minor`。

# Development Rules

1. 开始任何 TASK 前必须完整阅读：

   ```text
   AGENTS.md
   docs/PRD.md
   docs/ARCHITECTURE.md
   docs/UI_SPEC.md
   docs/TODO.md
   docs/DECISIONS.md
   ```

2. 每次只实现一个明确 TASK。
3. 不实现 TASK Scope 之外的功能。
4. 不以“顺便优化”为由修改大量无关代码。
5. 不擅自改变产品需求或总体架构。
6. 不擅自增加第三方依赖；确需增加时先记录 ADR 或取得明确批准。
7. 优先复用已有代码，修改前使用 `rg` 搜索类似实现。
8. 不创建重复 Repository、Service、Manager、DataSource、Indicator 或 Design Token。
9. 发现架构问题时先记录到 TODO/ADR，不偷偷大改。
10. 重大技术决策写入 `docs/DECISIONS.md`。
11. 保留用户未提交工作；禁止 `git reset --hard`，不得删除或覆盖无关改动。
12. Windows 构建优先使用：

    ```powershell
    .\gradlew.bat assembleDebug
    ```

    其他环境使用：

    ```bash
    ./gradlew assembleDebug
    ```

13. 当前构建要求 JDK 17 和 Android SDK Platform 36；先检查环境，不把环境缺失误判为代码缺陷。
14. 若存在测试，运行与当前 TASK 相关的测试；修改核心行情、策略或持久化逻辑时应运行 `testDebugUnitTest`。
15. 不允许通过删除已有功能或测试让 Build 通过。
16. 涉及后台、网络、通知的 TASK，在自动化测试之外还必须更新或执行相关真机验证矩阵。

# UI Rules

- 以 `docs/UI_SPEC.md` 为准，保持专业、简洁、高信息密度、Dark Theme 优先。
- 新 UI 使用集中定义的 Material 3 ColorScheme、Typography、Spacing、Shape 和组件规范；不得新增散落的颜色常量。
- 页面必须覆盖 Loading、Empty、Error、Disabled 等适用状态。
- 金融涨跌色只表达语义，不用颜色作为唯一信息载体。
- 当前手工 Tab/详情状态属于现状；未有 TASK 时不要擅自迁移 Navigation。

# Task Status

`docs/TODO.md` 只使用以下 TASK 状态：

```text
TODO
IN_PROGRESS
REVIEW
DONE
BLOCKED
REFACTOR
```

实现成熟度可使用：

```text
DONE
PROTOTYPE
IN_PROGRESS
TODO
REFACTOR
```

# Git

- 当前仓库使用 `main`，已有版本 Tag 和 GitHub Release workflow；不要改写历史。
- 在没有既有分支策略时，建议使用 `dev` 集成分支以及 `feature/TASK-xxx`、`fix/TASK-xxx` 工作分支。
- 创建、删除、合并或重写分支必须在用户授权范围内进行。
- 提交应聚焦一个 TASK，并在消息中包含 TASK ID。

# Definition of Done

一个 TASK 只有同时满足以下条件才可标记 `DONE`：

- Scope 已完成，Acceptance Criteria 全部满足。
- 没有额外实现无关需求或引入未经批准的依赖。
- `assembleDebug` 成功。
- 相关测试通过；若测试缺失，已明确记录缺口和手工验证方式。
- 没有明显新增 Crash，且没有明显破坏已有功能。
- 符合 Architecture Rules、UI Rules 和 Android Background Rules。
- Reviewer 的 Critical/Major 问题已关闭或被明确接受。
- 需要真机验证的内容已在 Android 16 真机验证，不能仅凭 Build 宣称完成。
- `docs/TODO.md` 已更新。
- 必要技术决策已写入 `docs/DECISIONS.md`。

# Android 16 真机与 Doze 测试

以下命令以正式包 `com.tzt.btcmonitor` 为例；Debug 包替换为 `com.tzt.btcmonitor.debug`。

## 1. 安装与基础验证

```powershell
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.tzt.btcmonitor/.MainActivity
adb logcat -c
adb logcat -v time -s BTCMonitor:I '*:S'
```

在 App 内：

1. 允许通知权限。
2. 点击“测试通知”，验证声音、振动、锁屏显示和 Heads-up。
3. 点击“测试行情获取”，等待三个端点全部完成；至少一个端点应显示成功、当前价格和耗时。此测试不依赖 Service 或策略。
4. 保存一个接近当前价格的条件。
5. 进入任一标的详情并启用至少一条提醒；App 应在前台自动启动监控。
6. 等待状态变成 WebSocket 已连接，当前价格和 Last Tick 持续更新。

如果三个端点都失败，先截图或提交诊断 Issue。结果会区分端点，并记录异常类型、底层 cause 和测试耗时，可用于判断 8443 端口、443 端口、TLS 或地区网络问题。

另开终端检查 Service 与进程：

```powershell
adb shell dumpsys activity services com.tzt.btcmonitor
adb shell pidof com.tzt.btcmonitor
adb shell dumpsys notification --noredact | Select-String -Pattern 'com.tzt.btcmonitor|monitor_service|trading_alert'
```

## 2. 前后台矩阵

每一步至少观察 3～5 分钟，并记录 Last Tick、最后连接/断开时间和日志：

1. App 前台。
2. Home 返回桌面。
3. 从最近任务中划掉 Activity；应出现 `onTaskRemoved`，Service 应继续（厂商行为除外）。
4. 重新打开 App，确认状态仍在更新。
5. 锁屏 10 分钟。
6. WiFi 切到 4G/5G，再切回 WiFi。应看到 `NetworkAvailable`、`WebSocketConnecting`，随后 `ReconnectSuccess` 或 `WebSocketConnected`。
7. 开飞行模式 1 分钟，再恢复网络。应看到 `NetworkLost`、`ReconnectScheduled` 和恢复连接。

不要用“强行停止”测试普通后台：强行停止是用户明确禁止 App 运行，系统不会允许 START_STICKY 绕过它。

## 3. 强制 Doze

先启动监控并确认 Tick 正常，然后让手机保持连接但不充电、锁屏：

```powershell
adb shell dumpsys battery unplug
adb shell input keyevent 26
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle
```

`dumpsys deviceidle` 应显示 deep state 为 `IDLE`。进入后立即记录：

```powershell
adb shell dumpsys activity services com.tzt.btcmonitor
adb shell pidof com.tzt.btcmonitor
adb logcat -d -v time -s BTCMonitor:I '*:S'
```

保持 10～30 分钟。`LastTick` 为节流日志，每 60 秒最多一条；Doze 正常情况下会暂停网络，因此它可能停止出现。每隔数分钟重复 Service/PID 检查。

### 结果分类

情况 A：Service Running，但 Last Tick 长时间不变化

- `dumpsys activity services` 仍有 `MarketMonitorService`，进程也可能存在。
- 日志没有新的 `LastTick`，或出现 WebSocket error/reconnect waiting。
- 结论：Service 存活，但 Doze/网络栈暂停了 WebSocket。不能记为 Service 被杀。

情况 B：Service Stopped

- `dumpsys activity services` 找不到服务，进程可能不存在。
- 结论：Service/进程被系统或厂商策略终止。记录手机型号、系统版本、电池模式、发生时间，以及退出 Doze 后是否因 START_STICKY 重建。

情况 C：Tick 仍持续

- 记录是否设备实际进入 deep `IDLE`、是否在电池优化白名单、是否充电。未真正进入 Doze 不能算持续联网成功。

## 4. 退出 Doze 与恢复

```powershell
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent 26
adb shell am start -n com.tzt.btcmonitor/.MainActivity
adb shell dumpsys activity services com.tzt.btcmonitor
```

预期：网络恢复后出现 `NetworkAvailable` 或 WebSocket failure，随后按退避序列重连；WebSocket 变成已连接，Last Tick 再次更新。若 Service 已停止且系统没有重建，用户需重新打开 App；存在启用提醒时会在前台自动恢复监控，这仍属于需要记录的可靠性结果。

## 5. 电池优化对照

查看白名单：

```powershell
adb shell dumpsys deviceidle whitelist
```

仅用于 A/B 测试，可临时加入/移除：

```powershell
adb shell dumpsys deviceidle whitelist +com.tzt.btcmonitor
adb shell dumpsys deviceidle whitelist -com.tzt.btcmonitor
```

白名单可能改变 Doze 行为，测试报告必须注明。应用本身不会请求 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。还应分别测试系统设置中的“优化/无限制”（名称依厂商不同）、省电模式、数据节省模式。

## 6. 条件触发测试

为了不依赖市场恰好跨越大价位，把 Above 阈值设到略高于现价或 Below 阈值设到略低于现价：

1. 先确认条件不满足。
2. 等待价格跨越，日志应有 `StrategyTriggered` 和 `NotificationSent`。
3. 条件持续满足时不得重复提醒。
4. 等价格返回不满足，再次跨越时应再次提醒。

### 多标的派发验证（TASK-003）

1. 同时为 BTC-USDT 与 ETH-USDT 各创建并启用一条接近现价、初始不满足的提醒。
2. 确认单一 WebSocket 已订阅两个 symbol，并同时观察两个标的的页面价格与日志。
3. 等待两个标的分别跨越各自阈值；两者都应出现各自的 `StrategyTriggered` 和 `NotificationSent`，一个 symbol 的高频 Tick 不得长期压制另一个 symbol。
4. 停用其中一个标的的全部提醒使其取消订阅，再重新启用；重新订阅后的首个有效 Tick 应能进入派发，不应继承取消前的 1 秒节流状态。
5. 停止并重新启动监控后重复检查，两个标的仍应独立更新和触发。

不依赖界面点按的真实行情自动化：

```powershell
.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.tzt.btcmonitor.market.Task003Android16Test"
```

该测试直接在真机中使用生产 `MarketDataManager` 订阅 BTC-USDT 与 ETH-USDT，等待两个 symbol 都收到有效 OKX Tick，并在结束时关闭测试连接。运行前需确保设备已授权 ADB、网络可访问 OKX，且没有同时运行生产监控 Service，避免测试期间形成重复连接。

同一测试类还使用可控的跨 symbol Tick 验证 BTC/ETH 提醒分别触发，并检查 App 发布了两条独立的 Android 通知；测试结束会删除自己创建的通知。这样不会把市场何时穿越阈值作为确定性测试条件。

Android 13+ 首次安装仍必须由用户授予通知权限。部分 Xiaomi/HyperOS 设备会在 `connectedDebugAndroidTest` 安装周期后撤销权限，并禁止 ADB/UiAutomation 静默授予；这类设备可在构建并安装 target/test APK 后人工允许一次通知，再直接运行 runner：

```powershell
adb shell am force-stop com.tzt.btcmonitor.debug
adb shell am instrument -w -r `
  -e class com.tzt.btcmonitor.market.Task003Android16Test `
  com.tzt.btcmonitor.debug.test/androidx.test.runner.AndroidJUnitRunner
```

若只需验证通知通道，始终使用“测试通知”；它不证明 WebSocket 或策略正常。

## 7. 建议测试记录

每轮保存：手机型号、Android build、App version、网络类型、电池优化模式、开始/结束时间、Service 是否存在、最后 Tick 时间、最后连接/断开时间、通知结果和相关日志。至少执行一次 8 小时锁屏测试和一次整夜测试。

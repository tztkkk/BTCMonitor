# Release、签名与应用内更新

## 1. 一次性创建签名密钥

在离线、安全的位置执行（示例参数可按需修改）：

```powershell
keytool -genkeypair -v -keystore btc-monitor-release.jks -alias btc-monitor -keyalg RSA -keysize 4096 -validity 10000
```

必须把原始 keystore、alias 和密码做至少两份离线备份。签名密钥丢失后，已安装的 `com.tzt.btcmonitor` 通常无法再被新 APK 覆盖升级；改 applicationId 会变成另一个 App，DataStore 也不会自动继承。

不要把 `.jks`、密码或 `keystore.properties` 提交到 Git。`.gitignore` 已排除这些文件。

## 2. 本地 Release 签名

在仓库根目录创建未提交的 `keystore.properties`：

```properties
KEYSTORE_PATH=C:/secure/btc-monitor-release.jks
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=btc-monitor
KEY_PASSWORD=your_key_password
```

构建并验证：

```powershell
./gradlew.bat clean test assembleRelease
$apksigner = "$env:LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/apksigner.bat"
& $apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

如果没有完整的四项签名配置，Gradle 可生成 unsigned release 供静态检查，但不能用于升级。CI 会通过 `apksigner verify` 阻止未签名 APK 发布。

## 3. GitHub Actions Secrets

将 keystore 转成单行 Base64（只复制结果到 GitHub Secret，不要保存进仓库）：

```powershell
$bytes = [IO.File]::ReadAllBytes('C:\secure\btc-monitor-release.jks')
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

在源码仓库 Settings → Secrets and variables → Actions 中创建：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Workflow 只在 runner 临时恢复 `release-keystore.jks`，完成后删除。GitHub 托管 runner 本身也是第三方 CI 环境；若威胁模型不接受把密钥交给 GitHub，应改为离线签名并手工上传 Release。

## 4. 版本与发布

每次发布先修改 `app/build.gradle.kts`：

```kotlin
versionCode = 2
versionName = "0.1.1"
```

`versionCode` 必须严格递增，`versionName` 使用 SemVer。提交后创建完全匹配的 tag：

```powershell
git tag v0.1.1
git push origin main
git push origin v0.1.1
```

`.github/workflows/release.yml` 会：checkout、安装 JDK 17、配置 Gradle、验证 tag/versionName、恢复 keystore、运行测试、构建签名 Release、用 apksigner 验证、生成 SHA-256、创建 GitHub Release，并上传：

```text
BTCMonitor-v0.1.1.apk
SHA256SUMS.txt
```

Tag 不是 `vMAJOR.MINOR.PATCH`、tag 与 versionName 不一致、测试失败、签名失败时都不会发布。

## 5. 应用内更新配置

在 App 设置页填写公开 Release 仓库的 owner/repo。App 调用匿名 GitHub Releases API，不带 PAT：

1. 启动时异步检查一次，失败不影响行情监控。
2. 用 SemVer 比较 versionName。
3. 下载 APK 和 `SHA256SUMS.txt` 并显示进度。
4. 校验 SHA-256。
5. 读取 APK，校验 applicationId、递增 versionCode 和签名证书。
6. 检查 `canRequestPackageInstalls()`；需要时打开 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`。
7. 通过 FileProvider 把 content URI 交给 Android Package Installer，最终安装必须由用户确认。

系统安装器会再次执行平台级包名、版本和签名检查；App 内校验是提前失败和明确报错，不是替代系统验证。

## 6. Private 源码 + Public Release 仓库

推荐结构：

```text
Private source repository -> CI signed APK
Public release repository  -> APK + release notes + SHA256SUMS.txt
Android App                -> only reads public release repository
```

当前 workflow 默认在源码仓库创建 Release。若源码仓库是 Private：

1. 新建只用于发布的 Public 仓库。
2. 使用具备该 Public 仓库 Contents write 权限的 fine-grained token，保存为源码仓库 Secret（例如 `RELEASE_REPO_TOKEN`）。
3. 将 Release step 配置为目标 public `owner/repo`，并把 token 传给发布 action；不要把 token 写进 YAML 明文。
4. App 设置页只填写 Public 仓库。

也可以在本地下载 CI artifact 后手工在 Public 仓库创建 Release。无论采用哪种方式，APK 都必须来自同一 keystore。

## 7. 2026/2027 Android 开发者验证

截至 2026 年 8 月，Android 正在推出 Google 认证设备上的开发者/包名验证。2026 年 9 月 30 日首阶段主要影响部分国家和参与商店，直接侧载在该首阶段不受同样限制；官方计划在 2027 年开始全球扩展。个人自用可以选择 Android Developer Console 的 limited distribution（最多 20 台设备）或按系统提供的 advanced flow，ADB 安装流程仍保留。

建议现在就用最终签名证书注册固定包名 `com.tzt.btcmonitor`，避免未来更新安装体验突然变化。此验证不会取代 APK 签名，也不会允许 App 绕过 Package Installer。参考：[Android developer verification](https://developer.android.com/developer-verification/guides) 与 [Android Developer Console 注册指南](https://developer.android.com/developer-verification/guides/android-developer-console)。

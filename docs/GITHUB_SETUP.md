# GitHub 更新与诊断日志设置

最简单、最稳定的第一阶段方案是使用一个 Public GitHub Repository，同时存放源码、Issues 和 Releases。App 匿名读取公开 Release，不需要也不会在 APK 中保存 GitHub Token。

## 1. 创建 GitHub Repository

在 GitHub 新建一个 Public repository，例如：

```text
Owner: tztkkk
Repository: BTCMonitor
```

确保仓库的 Issues 和 Actions 已启用。然后在当前项目目录执行：

```powershell
cd "D:\aihelpme\Android APP"
git init
git add .
git commit -m "Initial Android 16 BTC Monitor MVP"
git branch -M main
git remote add origin https://github.com/tztkkk/BTCMonitor.git
git push -u origin main
```

当前项目已经使用 `tztkkk/BTCMonitor`。`.gitignore` 已排除 APK、keystore、密码配置和构建目录。

## 2. 创建永久 Release 签名密钥

```powershell
keytool -genkeypair -v `
  -keystore "C:\secure\btc-monitor-release.jks" `
  -alias btc-monitor `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

请把原始 `.jks`、alias、keystore password 和 key password 做至少两份离线备份。密钥丢失后，已经安装的正式 App 将无法继续被新版本覆盖升级。

## 3. 配置 GitHub Actions Secrets

先把二进制 keystore 转换成单行 Base64：

```powershell
$bytes = [IO.File]::ReadAllBytes('C:\secure\btc-monitor-release.jks')
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

进入 GitHub repository：

```text
Settings
→ Secrets and variables
→ Actions
→ New repository secret
```

创建四个 Secrets：

```text
KEYSTORE_BASE64       刚才复制的完整 Base64
KEYSTORE_PASSWORD     keystore 密码
KEY_ALIAS             btc-monitor
KEY_PASSWORD          key 密码
```

Base64 只是二进制到文本的编码，真正的访问保护来自 GitHub Actions Secrets；不要把 Base64 写入源码、Issue 或普通 repository variable。GitHub 官方说明 Secrets 会在客户端加密后提交，并且只有显式引用它的 workflow 才能读取。

检查：

```text
Settings → Actions → General → Workflow permissions
```

允许 workflow 创建 Release。项目的 `release.yml` 已声明：

```yaml
permissions:
  contents: write
```

若组织策略强制只读，需要由仓库/组织管理员允许相应权限。

## 4. 发布第一个正式版本

当前源码版本为：

```text
versionCode = 2
versionName = 0.1.1
```

提交代码后创建完全匹配的 tag：

```powershell
git add .
git commit -m "Release v0.1.1"
git push origin main
git tag v0.1.1
git push origin v0.1.1
```

GitHub Actions 会自动执行测试、R8、签名验证和 SHA-256 计算，并在 Releases 中生成：

```text
Monitor-v0.1.1.apk
SHA256SUMS.txt
```

进入 GitHub 的 Actions 页面确认 workflow 为绿色，然后进入 Releases 下载 APK。

## 5. 第一次安装正式版

你当前安装的是 Debug 包：

```text
com.tzt.btcmonitor.debug
```

正式包是：

```text
com.tzt.btcmonitor
```

二者会并存。请从第一个 GitHub Release 手动下载并安装正式 APK；从第二个正式版本开始，App 内更新才能验证“相同 applicationId + 相同签名证书 + 更高 versionCode”并覆盖安装。

## 6. 在 App 中设置更新仓库

打开正式 App：

```text
设置 → GitHub Release 仓库
```

填写：

```text
GitHub owner: tztkkk
Release repository: BTCMonitor
```

保存后点击首页“检查更新”。App 启动时也会异步检查一次；GitHub 不可访问不会影响行情监控。

验证更新流程时，把下一版改为：

```kotlin
versionCode = 3
versionName = "0.1.2"
```

提交并推送 `v0.1.2` tag。旧的 v0.1.1 正式 App 应显示新版本，下载后依次校验 SHA-256、包名、versionCode 和签名，再打开 Android 系统安装确认页。

## 7. 提交运行日志

App 的“调试日志”页面有两个按钮：

### 导出 / 分享完整日志

生成 `.txt` 诊断报告，包含设备、版本、通知权限、电池优化、Service、网络、WebSocket 状态和最多 500 条日志。Android 分享面板可以把文件保存到文件管理器、发送到当前对话，或手工附加到 GitHub Issue。

### 在 GitHub 新建日志 Issue

浏览器会打开：

```text
https://github.com/tztkkk/BTCMonitor/issues/new
```

并预填设备状态和最近 20 条日志。App 不会自动提交；请先检查内容，再由你登录 GitHub 点击 Submit。Public Issue 创建完成后，把 Issue 链接发给我，我就可以通过该链接读取和分析。

不要在日志说明中加入 GitHub Token、keystore 密码、交易所 API Key 或其他凭据。当前 MVP 本身不保存交易所 API Key。

## 8. Private 源码仓库

如果源码必须保持 Private，推荐另建 Public release repository，只发布 APK、Release notes、checksum 和日志 Issues。App 设置中填写 Public 仓库。

当前 workflow 默认把 Release 发布到源码所在仓库。跨仓库发布需要额外的 fine-grained token，并仅授予 Public release repository 的 Contents write 权限，将其保存为 Actions Secret；不能把该 token 写进 App。第一阶段如果不希望增加此复杂度，可以从 Private CI 下载签名 artifact，再手工上传到 Public Release repository。

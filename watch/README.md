# 期货信号 · Watch 版（Galaxy Watch 5 / Wear OS）

把手机版（`android/`）的核心功能做到手表上：**实时行情订阅 → 5m/60m 策略信号检测 → 手腕震动/通知提醒**，可选飞书推送。

## 功能对照

| 功能 | 手机版 | 手表版 |
|---|---|---|
| 天勤行情订阅（DCE.a2609 5m） | ✅ | ✅（同一套 `DiffMdClient`） |
| 开仓信号检测（5m / 60m 双策略） | ✅ | ✅（同一套 `ChannelSignalDetector`） |
| 声音 / 震动 / 通知 | ✅ | ✅（震动为主，通知全屏弹出） |
| 飞书推送 | ✅ 默认开 | ✅ 默认**关**（避免与手机重复推送） |
| 图表盘面 | ✅ WebView | ❌ 手表只显最新价/状态/最近信号 |
| 策略选择 | 设置页持久化 | 设置页持久化（`strategy_profile`） |

## 目录与复用

核心逻辑 6 个文件与 `android/app` 保持同源拷贝（`ShinnyAuth` / `DiffMdClient` /
`ChannelSignalDetector` / `OpenSignalNotifier` / `FeishuAppNotifier` / `FeishuWsClient`），
**修改这些文件时两端需同步**。手表特有：`WatchMarketService.kt`（精简版前台服务）+
`MainActivity.kt`（Wear Compose UI：主页/设置/登录）。

## 构建

- CI：push 到 `watch/**` 自动在 GitHub Actions 构建，产物 `channel-strategy-watch-apk`
- 本地：`cd watch; gradle assembleRelease`（需 ANDROID_HOME / JDK 17）

## 安装到手表

1. 手表：设置 → 关于 → 连点版本号开启开发者模式
2. 手机 Galaxy Wearable → 手表设置 → 开发者选项 → 打开「Wi-Fi 调试」与「ADB 调试」
3. `adb pair <手表IP:端口>`（手表屏幕上显示配对码）→ `adb connect <手表IP:端口>`
4. `adb install app-release.apk`

也可用 Android Studio 直接 Deploy 到已连接的手表。

## 首次使用

1. 启动 App → 输入天勤账号密码（手表键盘/手机输入法）→ 登录并开始监控
2. 设置页可切换 5m/60m 策略、开关提醒/震动
3. 出现开多/开空信号时：强震动 + 亮屏 + 通知

## 注意

- 手表依赖 Wi-Fi/蓝牙网络连接；熄屏后靠前台服务 + WakeLock 保活，长时间熄屏仍可能被系统限制，重要场景建议手机端同时在线
- 飞书推送默认关闭：手机和手表都开会收到两条

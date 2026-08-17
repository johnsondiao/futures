# 期货信号 · Watch 版（小米手表 5 / Wear OS 系）

把手机版（`android/`）的核心功能做到手表上：**实时行情订阅 → 5m/60m 策略信号检测 → 手腕震动/通知提醒**，可选飞书推送。

> 目标设备：小米手表 5（Xiaomi HyperOS，基于 Android 定制，支持 ADB 侧载 APK）。
> 代码只用标准 Android / Wear OS API，无品牌专属依赖，理论上任何能装 APK 的手表都可用。

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

## 安装到小米手表 5

**手表开启开发者模式（小米路径）：**

1. 手表与手机连同一个 Wi-Fi
2. 设置 → 我的设备 → 关于本机 → 找到「手表系统」，**连续点击版本号**直到提示"开发者选项已打开"
3. 返回设置 → 最下方「开发者选项」→ 打开「**无线调试**」（同时确保 ADB 调试已开）
4. 点「与新设备配对」，屏幕显示 IP、端口、配对码

**安装（三种方式任选）：**

```bash
# 方式一：电脑 adb
adb pair <手表IP:配对端口>     # 输入手表显示的配对码
adb connect <手表IP:连接端口>
adb install app-release.apk
```

- 方式二：手机装「WearOS工具箱」App → 填手表 IP/端口配对 → 安装本地 APK
  （小米 HyperOS 可能拒绝普通 ADB 安装，若失败请在工具箱设置里打开「使用特殊的安装方式」）
- 方式三：手表能上网时，装一个手表端文件管理器/APK 安装器从浏览器下载

## 首次使用

1. 启动 App → 输入天勤账号密码（手表键盘/手机输入法）→ 登录并开始监控
2. 设置页可切换 5m/60m 策略、开关提醒/震动
3. 出现开多/开空信号时：强震动 + 亮屏 + 通知

## 注意

- **兼容性待实测**：小米手表 5 是 HyperOS 定制系统（非原生 Wear OS），侧载 APK 可行但
  个别系统可能限制第三方 App 常驻；若安装或运行异常，优先尝试 WearOS工具箱的特殊安装方式
- 小米手表没有谷歌服务框架，本应用已把 wearable 库设为可选（`required=false`），不受影响
- 手表依赖 Wi-Fi/蓝牙网络连接；熄屏后靠前台服务 + WakeLock 保活，长时间熄屏仍可能被系统限制，重要场景建议手机端同时在线
- 飞书推送默认关闭：手机和手表都开会收到两条
- 若手表实在装不了 APK：备选方案是让手机端 App 推通知、由小米运动健康转发到手表（所有手表都支持通知镜像，无需装 App）

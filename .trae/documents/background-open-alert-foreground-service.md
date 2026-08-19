# 后台开仓声音提醒 — 前台服务 + 原生信号检测

## Context（为什么做这个改动）

app 在后台运行时错过开仓声音提醒。根因有三：

1. **进程优先级低 + Doze 网络限制**：[MainActivity.kt](file:///d:\vibecoding\futures\android\app\src\main\java\com\futures\channel\MainActivity.kt) 直接持有 `DiffMdClient`，无前台服务保活，后台时进程易被回收、WebSocket 被 Doze 冻结。
2. **WebView.onPause 暂停 JS**：Activity 切后台后，`webView.evaluateJavascript("window.__onRawBars(...)")` 注入的代码不执行，[app.js](file:///d:\vibecoding\futures\android\app\src\main\assets\www\app.js) 的 `maybeAlertOpens` 不跑，开仓标记检测不到。
3. **Web Audio 后台静音**：`playBeep` 在后台发不出声。

[AndroidManifest.xml](file:///d:\vibecoding\futures\android\app\src\main\AndroidManifest.xml) 未声明 `FOREGROUND_SERVICE`/`WAKE_LOCK`，也未注册任何 Service。

**目标**：app 切后台、熄屏后仍能持续拉取行情、检测开仓信号并触发声音/震动/通知。

**选定方案**：原生移植开仓检测 + 前台服务（specialUse 类型）+ WAKE_LOCK 强保活。开仓信号检测从 JS 下沉到 Kotlin 原生层，不依赖 WebView JS 执行；声音/震动/通知统一由原生触发，避免前后台双响。

环境：compileSdk=34, targetSdk=34, minSdk=24, applicationId=com.futures.channel，debug key 个人分发（不上 Play Store）。

---

## 文件改动清单

### 新建（2 个）

| 文件 | 作用 |
|------|------|
| `android/app/src/main/java/com/futures/channel/MarketForegroundService.kt` | 前台服务，持有 DiffMdClient + OpenSignalNotifier + ChannelSignalDetector，统一生命周期、重连、响铃 |
| `android/app/src/main/java/com/futures/channel/ChannelSignalDetector.kt` | 原生移植 [strategy.js:8-223](file:///d:\vibecoding\futures\android\app\src\main\assets\www\strategy.js#L8-L223) 的开仓判断逻辑（通道 MA + CCI 金叉/死叉 + countInWindow 去重） |

### 修改（5 个）

| 文件 | 改动要点 |
|------|---------|
| [AndroidManifest.xml](file:///d:\vibecoding\futures\android\app\src\main\AndroidManifest.xml) | +3 权限（FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、WAKE_LOCK）+ `<service>` 声明（foregroundServiceType=specialUse + subtype property） |
| [MainActivity.kt](file:///d:\vibecoding\futures\android\app\src\main\java\com\futures\channel\MainActivity.kt) | 删除直接持有的 DiffMdClient/openNotifier；改为 bindService + Listener 模式；onResume 注册 listener、onPause 注销；登录后把 Session 传给 Service |
| [DiffMdClient.kt](file:///d:\vibecoding\futures\android\app\src\main\java\com\futures\channel\DiffMdClient.kt) | 新增 `onAuthFailure` / `onDisconnect` 回调参数；不在内部重连，由 Service 统一调度 |
| [app.js](file:///d:\vibecoding\futures\android\app\src\main\assets\www\app.js) | `fireOpenAlert` 只保留 `showAlertToast`（前台可视化）；**删除** `ChannelBridge.notifyOpenSignal` 调用、`playBeep`、浏览器 `Notification` 分支——声音/震动/通知全归原生 detector |
| [OpenSignalNotifier.kt](file:///d:\vibecoding\futures\android\app\src\main\java\com\futures\channel\OpenSignalNotifier.kt) | 不变（`notifyOpen` 已满足 Service 调用需求） |

### 不变

`strategy.js`、`ShinnyAuth.kt`、`build.gradle.kts`（不引入新依赖）、所有 res 文件。

---

## 关键设计

### 1. MarketForegroundService 骨架

- 继承 `Service`（非 LifecycleService，避免引入 `lifecycle-service` 依赖）
- 持有：`DiffMdClient` + `OpenSignalNotifier` + `ChannelSignalDetector` + `PARTIAL_WAKE_LOCK`
- `onCreate`：创建通知渠道 `market_service_v1`（IMPORTANCE_LOW 静默）、acquire WAKE_LOCK（12h 上限防泄漏）
- `onStartCommand`：5s 内 `startForeground(NOTIFY_ID=4100, 通知)`；从 Intent extras 取 user/pass/accessToken/mdUrl；处理 intent==null（START_STICKY 重启场景，从 EncryptedSharedPreferences 读 user/pass 重登）；返回 START_STICKY
- `LocalBinder` + `Listener` 接口供 Activity 绑定：
  - `onBars(bars)` — 前台时推给 WebView
  - `onStatus(msg)` — 状态文本
  - `onSessionExpired()` — 前台时通知 Activity 重登
- `handleBars(bars)`：先 `listener?.onBars(bars)`（前台推 WebView），再 io 线程跑 `detector.detect(bars)`，新信号 → `notifier.notifyOpen(...)`
- 前台通知：复用 `ic_stat_notify` 图标，点击 PendingIntent 回 MainActivity，文案随状态更新（"行情服务运行中" / "行情已连接 · N 根"）

### 2. ChannelSignalDetector 移植

移植 [strategy.js:8-148](file:///d:\vibecoding\futures\android\app\src\main\assets\www\strategy.js#L8-L148) 的 8 个辅助函数到 Kotlin：`ma`/`ref`/`barsLast`/`valueWhen`/`countInWindow`/`existInWindow`/`avedev`/`rollingSum`（纯数值计算，签名见 Plan 设计）。

开仓判断核心（对齐 strategy.js:150-223）：
- 通道：`upper=ma(high,60)`, `lower=ma(low,60)`, `color=valueWhen(side≠0, side)`
- CCI：`typ=(h+l+c)/3`, `cci=(typ-ma(typ,15))/(0.015*avedev(typ,15))`, `cciMa=ma(cci,4)`
- 金叉/死叉：`cci>cciMa 且 前根 cci≤cciMa` / 反之
- `openLong = candLong && countInWindow(candLong, barsRed+1)==1`（窗口去重，只取第一个）
- 参数硬编码对齐：channel_n=60, cci_p=15, cci_m=4

**去重**：维护 `seenKeys: MutableSet<String>`，键格式 `"$time|L"` / `"$time|S"`（与 [app.js collectOpenKeys](file:///d:\vibecoding\futures\android\app\src\main\assets\www\app.js#L499-L508) 一致）。首次 `detect` 时所有历史 key 被 add 进 set，不返回新信号——天然实现 app.js 的"首次基线不响"语义，无需额外分支。`seenKeys` 在 Service 进程内持续累积，不随 Activity 切换丢失。

### 3. Service ↔ MainActivity 通信

**Binder + Listener**（单进程最简，无新依赖，无序列化开销）：
- `onCreate`：登录成功后 `startForegroundService(intent with extras)` + `bindService(BIND_AUTO_CREATE)`
- `onResume`：`service?.setListener(activityListener)`（前台接收 bars）
- `onPause`：`service?.setListener(null)`（停止推 WebView，Service 继续 detector）
- `onDestroy`：`unbindService(conn)`（不 stopService，前台服务继续跑）
- `maybePushBars` 复用现有节流逻辑，`pendingBars` 来源改为 `activityListener.onBars`

### 4. 去重（避免前后台双响）

**单一响铃来源**：原生 `ChannelSignalDetector` 是声音/震动/通知的唯一触发点。`app.js` 的 `fireOpenAlert` 只保留 `showAlertToast`（前台可视化增强），删除 `playBeep` 和 `ChannelBridge.notifyOpenSignal` 调用。这样：
- 前台：detector 触发原生声音/震动/通知 + JS 显示 toast
- 后台：detector 触发原生声音/震动/通知（WebView 暂停无影响）
- 不会双响

`MainActivity.Bridge.notifyOpenSignal` 接口保留并标 `@Deprecated`（兼容旧 JS，新 JS 不再调用）。

### 5. AndroidManifest 改动

```xml
<!-- 新增权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- application 内新增 -->
<service
    android:name=".MarketForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="实时行情订阅与开仓信号提醒，需持续网络连接" />
</service>
```

选 `specialUse` 而非 `dataSync`：dataSync 在 Android 14 有 6h/24h 累计限制，期货交易时段（白盘+夜盘≈8h）会超限；specialUse 个人分发无需 Play Store 审查。

### 6. DiffMdClient 改动

新增两个回调参数：
- `onAuthFailure: () -> Unit` — `onFailure` 中判断 HTTP 401/关闭码 1008 时触发
- `onDisconnect: () -> Unit` — 其他 `onFailure`/`onClosed` 异常时触发

不在 DiffMdClient 内部重连。Service 的 `scheduleReconnect`（5s 延迟）统一处理：
- 网络断开 → 用原 session 重连
- Session 过期 → 后台用保存的 user/pass 调 `ShinnyAuth.login` 重登；前台时 `listener?.onSessionExpired()` 让 Activity 走现有 `reconnect()`

---

## 风险与 Mitigation

| 风险 | Mitigation |
|------|-----------|
| Doze 仍可能延迟网络（前台服务+WAKE_LOCK 不完全免疫） | 后续可加电池白名单引导（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），本期先观察交易时段表现 |
| `specialUse` subtype 声明错误导致启动崩溃 | manifest `<property>` 必填，Android 14 设备测试覆盖 |
| START_STICKY 重启 intent==null 拿不到 session | onStartCommand 处理 null：从 EncryptedSharedPreferences 读 user/pass 重登 |
| detector 与 JS 计算不一致（漏报/误报） | 写对照测试：同组 bars 喂 Node 跑 strategy.js 和 JUnit 跑 detector，比对 open_long/open_short 完全一致 |
| `ToneGenerator(STREAM_NOTIFICATION)` 后台可能被限制 | 若实测后台无声，改 `STREAM_ALARM` + `USAGE_ALARM`（更抗 Doze，但更打扰） |

---

## 验证步骤

1. **单元对照测试**：录制真实 5m K 线 JSON（≥100 根），Node 跑 strategy.js 输出 `open_long`/`open_short`，JUnit 跑 `ChannelSignalDetector.detect(bars)`，断言开仓 time 列表完全一致。
2. **前台功能**：登录连接行情，确认状态栏有"行情服务运行中"常驻通知；等待新 K 线，触发开仓信号时响铃+震动+通知+toast，且**只响一次**（无双响）。
3. **后台核心场景**：按 Home 退后台 → 锁屏 5-10 分钟（覆盖新 K 线）→ `adb shell dumpsys deviceidle force-idle` 模拟 Doze → 验证开仓时仍响铃/震动/弹通知。
4. **前后台切换连续性**：后台→回前台，WebView 图表与 detector 状态一致，无遗漏标记、无重复响。
5. **进程被杀恢复**：`adb shell am force-stop com.futures.channel` → 等 START_STICKY 重启 → 验证 Service 自动重连行情。
6. **Session 过期**：等待或人工触发 token 过期 → logcat 看 `onAuthFailure`→后台重登→重连，无需用户干预。

## 实施顺序

1. AndroidManifest 权限 + Service 声明
2. `ChannelSignalDetector.kt` 移植 + 对照测试
3. `DiffMdClient.kt` 加 onAuthFailure/onDisconnect 回调
4. `MarketForegroundService.kt` 骨架 + 集成 DiffMdClient + detector
5. `MainActivity.kt` 改造为 bindService + Listener
6. `app.js` fireOpenAlert 精简（只留 toast）
7. 端到端验证（前台→后台→Doze→进程恢复）

实施后调用 TRAE-code-review skill 审查 diff。

# 安卓 App（通道策略 · 本机直连天勤）

手机打开 App 即可用：**不依赖家里电脑服务**。

## 架构

```
安卓 App
  ├─ 登录快期账号（EncryptedSharedPreferences 存本机）
  ├─ OkHttp WebSocket 直连天勤 DIFF 行情网关
  ├─ assets/www：K 线盘面 + JS 策略引擎
  └─ 输出：条件单（开仓/止盈1–3/止损）+ 机会评估
```

说明：天勤 **没有官方 Android SDK**；官方 TqSdk 仅 Python。本 App 用底层 DIFF 协议 + 本机策略计算。

## 使用

1. GitHub Actions 编译 APK 并安装
2. 打开 App，输入快期账户/密码登录
3. 等待行情连接后查看盘面；下拉可重连

默认合约：`DCE.a2609`，周期：5 分钟。

## GitHub Actions 编译

1. 推送 `android/` 相关改动
2. Actions → **Build Android APK** → 等待完成
3. 下载 Artifact：`channel-strategy-apk`

也可：Actions → Run workflow 手动触发。

## 电脑调试（可选）

仓库仍保留 `python scripts/run_ths_live.py`，用于浏览器对照盘面；**App 主路径不再连该服务**。

策略对拍：

```bash
python scripts/export_strategy_compare.py
node scripts/compare_strategy.mjs
```

DIFF 探针（需 `.env` 里 `TQ_USER`/`TQ_PASS`）：

```bash
python scripts/probe_diff_md.py
```

## 注意

- 需要手机能访问公网（鉴权与行情）
- 快期密码只存在本机加密存储，不会发到你的自建服务器
- 社区/自研 DIFF 接入非官方长期承诺，网关变更时可能需适配

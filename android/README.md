# 安卓 App（通道策略实时盘）

手机 App 通过 WebView 打开本仓库的实时服务页面（竖屏自上而下）：

1. **主图 K 线**（通道 + 开/平/止盈标注 + 当前条件价水平线；CCI 默认可折叠）
2. **条件单卡片**（要不要挂 / 怎么挂：开仓·止盈·止损）
3. **机会评估**（本合约历史样本回放的触发/先盈/先损概率与期望点数）

## 架构

```
安卓 App (WebView)
    │  WiFi / 公网
    ▼
电脑或云主机: python scripts/run_ths_live.py
    │
    ├─ 天勤 TqSdk 实时 K 线（THS_MODE=live/auto）
    └─ 本地缓存回退（THS_MODE=cache）
```

> 天勤账号跑在服务端，手机不存密码。手机只连你的服务地址。

## 1. 启动实时服务（电脑）

```bash
pip install -r requirements.txt
# .env 里填 TQ_USER / TQ_PASS

# 本机可访问局域网
python scripts/run_ths_live.py --host 0.0.0.0 --port 8080
```

环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `THS_SYMBOL` | `DCE.a2609` | 合约 |
| `THS_PERIOD` | `5m` | 周期 |
| `THS_MODE` | `auto` | `auto`/`live`/`cache` |
| `THS_BARS` | `300` | 图上显示根数 |

浏览器先打开 `http://电脑局域网IP:8080` 确认条件单卡片正常。

## 2. 用 GitHub Actions 编译 APK

1. 推送代码到 GitHub
2. Actions → **Build Android APK** → Run workflow
3. 可选填写默认服务地址，例如 `http://192.168.1.8:8080`
4. 下载 Artifact：`channel-strategy-apk`

安装到手机后，顶部可改服务地址并点「连接」。

## 3. 本机编译（可选）

```bash
cd android
gradle wrapper --gradle-version 8.7
./gradlew assembleRelease -PSERVER_URL=http://192.168.1.8:8080
```

APK：`android/app/build/outputs/apk/release/`

## 注意

- 手机与电脑需同一局域网，或把服务部署到有公网 IP / 内网穿透的主机
- 模拟器调试默认 `http://10.0.2.2:8080`（宿主机回环）
- 开仓触发价为 CCI 交叉近似值；正式开仓仍以收盘信号为准

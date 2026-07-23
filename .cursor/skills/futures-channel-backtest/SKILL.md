---
name: futures-channel-backtest
description: >-
  本项目专用：天勤 TqSdk 取国内期货 K 线，经 DataFrame 桥接后用 backtrader
  回测「均线高低通道 + 看大做小」策略。当用户提到本仓库回测、通道策略、
  看大做小、ATR 止盈、天勤拉数喂 backtrader 时使用。
---

# 本项目回测工作流

## 技术栈（已定）

- 数据：**天勤 TqSdk**（不要用 akshare）
- 回测：**backtrader**
- 规则文档：`docs/strategy_rules.md`
- 详细 API：先读 `.cursor/skills/tqsdk-trading-and-data/` 与 `.cursor/skills/backtrader/`

## 取数

```python
from src.data.tq_loader import fetch_klines
df = fetch_klines("KQ.m@SHFE.rb", period="60m", data_length=2000)
# period: 5m | 60m | 1d
```

- 账号：`.env` 里 `TQ_USER` / `TQ_PASS`
- 主力连续：`KQ.m@交易所.品种`，如 `KQ.m@SHFE.rb`
- 免费单次约最近 8000～10000 根；更长历史需专业版 `DataDownloader`

## 喂给 backtrader

```python
from src.data.bt_feed import make_data, TIMEFRAME_MAP
tf, comp = TIMEFRAME_MAP["60m"]
data = make_data(df, name="rb_60m", timeframe=tf, compression=comp)
cerebro.adddata(data)
```

多周期「看大做小」时：分别拉 60m 与 5m，各 `adddata` 一次；大周期用 `self.datas[1]`，小周期用 `self.datas[0]`（或命名约定写清）。

## 通道变色（已明确）

- 价格在**上轨之上** → 红色（多）
- 价格在**下轨之下** → 绿色（空）
- 通道内部 → 保持上一颜色
- **变色** = 从一侧跨过整段通道到另一侧（红↔绿）
- 判定价：**该周期 K 线收盘价**（5m 用 5m 收盘，60m 用 60m 收盘）

## CCI 开仓（参数已明确，组合待遍历）

- CCI(15) + CCI 的 SMA(4)，算在 **5m**
- 与通道组合方式未定，用回测遍历候选模式（见 `docs/strategy_rules.md`）
- 配置：`CCI_PERIOD` / `CCI_SIGNAL_PERIOD` / `CCI_ENTRY_MODE`

## ATR 止盈（已明确）

- 周期 14，算在 **5m**，每根 K 线**滚动**重算目标
- 开 **3 手**：3× / 5× / 7× ATR 各平 1 手（收盘价触及）
- 多：`入场 + k×ATR`；空：`入场 - k×ATR`
- 剩余仓：**5m 反向变色即平**（持多变绿 / 持空变红）；60m 只做开仓过滤
- 参数：`src/config.py`（`ATR_PERIOD` / `ATR_TP_MULTS` / `ENTRY_LOTS`）

## 通道默认参数（可覆盖）

见 `src/config.py`：

| 周期 | 默认 N |
|------|--------|
| 5m | 144 |
| 60m | 60 |

上轨=`MA(High,N)`，下轨=`MA(Low,N)`。

## 策略演进

1. v0.1 `strategies/channel_trend.py`：单周期变色开平
2. v0.2：60m(N=60) 过滤 + 5m(N=144) 入场 + 禁止同色再开
3. v0.3：ATR 滚动分批止盈 + 剩余仓 5m 反向变色即平

未确认规则标在 `docs/strategy_rules.md` 的 `[待确认]`，改代码前先问用户拍板。

## 运行

```bash
python scripts/fetch_sample.py
python scripts/run_backtest.py
```

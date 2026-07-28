# 期货趋势通道回测系统

数据：**天勤 TqSdk**　回测：**backtrader**  
策略：5 分钟高低通道变色 + CCI 确认 + ATR 分批止盈（见 `docs/交易规则_定稿.md`）

## 环境

```bash
python -m venv .venv
.\.venv\Scripts\activate          # Windows
pip install -r requirements.txt
copy .env.example .env            # 填入快期账号密码
```

## 文档与同花顺公式

| 文件 | 说明 |
|------|------|
| [`docs/交易规则_定稿.md`](docs/交易规则_定稿.md) | 当前定稿交易规则 |
| [`docs/同花顺_主图_高低通道.txt`](docs/同花顺_主图_高低通道.txt) | 主图：通道 + 变色 |
| [`docs/同花顺_副图_CCI.txt`](docs/同花顺_副图_CCI.txt) | 副图：CCI(15)/MA(4) 开仓提示 |

同花顺：公式管理 → 新建主图/副图 → 粘贴对应 txt → 编译，图表用 **5 分钟**。

## 常用命令

```bash
python scripts/run_backtest.py --symbol DCE.a2609 --period 5m
python scripts/export_chart.py --symbol DCE.a2609 --period 5m --open
python scripts/ths_indicator_sim.py --bars 500 --open
python scripts/run_ths_live.py --host 0.0.0.0 --port 8080   # 可选：电脑浏览器对照盘面
```

回测图：`data/reports/DCE_a2609_5m_chart.html`  
安卓 App：见 [`android/README.md`](android/README.md)（本机直连天勤，GitHub Actions 编译 APK）

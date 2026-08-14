# 期货趋势通道回测系统

数据：**天勤 TqSdk**　回测：**backtrader**　安卓：行情提醒 App
策略：高低通道变色 + CCI 确认 + ATR 分批止盈（5 分钟 / 60 分钟双周期，见 `spec/交易规则_定稿.md`、`spec/strategy_60m.md`）

## 目录结构

```
spec/      产品与策略文档（产品需求文档、交易规则定稿、60m 策略说明、同花顺公式）
pc/        PC 端代码（回测、参数寻优、统计导出、同花顺模拟/实时对照服务）
  scripts/   可执行脚本
  src/       配置与数据加载（config / tq_loader / ths_sim）
  strategies/  backtrader 策略类
  server/    ths_live 对照服务
  data/      K 线缓存 / 寻参日志 / 回测报告
android/   安卓 App（行情盘面 + 开仓提醒 + 飞书推送，GitHub Actions 编译 APK）
```

## 环境（PC 端）

```bash
python -m venv .venv
.\.venv\Scripts\activate          # Windows
pip install -r pc/requirements.txt
copy pc\.env.example pc\.env      # 填入快期账号密码
```

## 常用命令

```bash
python pc/scripts/run_backtest.py --symbol DCE.a2609 --period 5m    # 或 --period 60m
python pc/scripts/export_chart.py --symbol DCE.a2609 --period 5m --open
python pc/scripts/ths_indicator_sim.py --bars 500 --open
python pc/scripts/run_ths_live.py --host 0.0.0.0 --port 8080        # 可选：浏览器对照盘面
python pc/scripts/delay_sensitivity.py --period 60m                 # 开仓延迟敏感性
```

> 脚本以自身位置定位项目根（`pc/`），在任意工作目录下执行均可。

## 文档索引

| 文件 | 说明 |
|------|------|
| [`spec/产品需求文档.md`](spec/产品需求文档.md) | 双端产品需求（PC 回测选参 + 安卓提醒） |
| [`spec/交易规则_定稿.md`](spec/交易规则_定稿.md) | 5 分钟策略定稿规则（唯一权威文本） |
| [`spec/strategy_60m.md`](spec/strategy_60m.md) | 60 分钟策略（人工执行友好版）定参与依据 |
| [`spec/strategy_rules.md`](spec/strategy_rules.md) | 策略规则草案 |
| [`spec/同花顺_主图_高低通道.txt`](spec/同花顺_主图_高低通道.txt) / [`spec/同花顺_副图_CCI.txt`](spec/同花顺_副图_CCI.txt) | 同花顺公式 |

同花顺：公式管理 → 新建主图/副图 → 粘贴对应 txt → 编译，图表用 **5 分钟**。

回测图：`pc/data/reports/DCE_a2609_5m_chart.html`
安卓 App：见 [`android/README.md`](android/README.md)（本机直连天勤，GitHub Actions 编译 APK）

"""策略默认可调参数。

入场逻辑（稳健组，见 scripts/robustness_report.py）：
  entry_mode=color_then_cci / channel=60 / CCI(15,4)

止盈倍数选用 **1.5 / 3 / 5 × ATR(20)**（滚动）：
  样本内胜率约 48%、净利最高档之一；第一档比 0.5ATR 远，更容易覆盖手续费。
  测算见 scripts/scan_atr_winrate.py。
"""

# 均线高低通道周期 N（上轨=MA(High,N)，下轨=MA(Low,N)）
CHANNEL_N = {
    "5m": 60,
    "60m": 60,
}
CHANNEL_N["1h"] = CHANNEL_N["60m"]

# ATR 止盈（在 5 分钟周期上计算）
ATR_PERIOD = 20
ATR_TIMEFRAME = "5m"
ATR_TRAILING = True
ATR_TP_MULTS = (1.5, 3, 5)

# 固定 3 手
ENTRY_LOTS = 3
EXIT_REMAINING_ON = ("5m_color_reverse",)

# CCI 开仓过滤（5 分钟）
CCI_PERIOD = 15
CCI_SIGNAL_PERIOD = 4
CCI_TIMEFRAME = "5m"
CCI_ENTRY_MODE = "color_then_cci"

# ============================================================
# 60 分钟策略（人工执行友好版，正式参数）
#
# 背景：5m 版单笔期望只有 ≈100 元/3手，人工下单延迟 10 分钟即转负。
# 60m 单笔边际大 4~5 倍，对执行延迟宽容得多。
# 寻优见 scripts/search_60m.py（稳健区方法：ATR 退出怎么调都盈利的入场逻辑）；
# 延迟验证见 scripts/delay_sensitivity.py --period 60m。
#
# DCE.a2609 60m（2025-09~2026-08，1523 根）样本内表现：
#   29 笔 / 胜率 72% / 平均 +551 元/笔；
#   迟 1 根(1小时)+滑点1元 → +11634；迟 2 根(2小时) → +8819，仍为正。
# ============================================================
STRATEGY_60M = {
    "channel_period": 60,          # 60m 高低通道 N
    "cci_period": 15,              # CCI 周期
    "cci_signal": 3,               # CCI 信号线 SMA
    "atr_period": 20,              # ATR 周期（60m 上计算，滚动更新目标）
    "atr_mults": (0.8, 1.5, 2.5),  # 三档分批止盈 × ATR
    "entry_lots": 3,               # 固定 3 手
    "entry_mode": "color_then_cci",  # 变色后等 CCI 金叉/死叉确认
    "atr_trailing": True,          # 止盈目标用最新 ATR 滚动重算
}

# 按周期索引的策略参数表（新增周期时在 search_*.py 里先寻优再加进来）
STRATEGY_PROFILES = {
    "5m": {
        "channel_period": CHANNEL_N["5m"],
        "cci_period": CCI_PERIOD,
        "cci_signal": CCI_SIGNAL_PERIOD,
        "atr_period": ATR_PERIOD,
        "atr_mults": ATR_TP_MULTS,
        "entry_lots": ENTRY_LOTS,
        "entry_mode": CCI_ENTRY_MODE,
        "atr_trailing": ATR_TRAILING,
    },
    "60m": STRATEGY_60M,
}

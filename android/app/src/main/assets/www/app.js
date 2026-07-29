(() => {
  const el = {
    meta: document.getElementById("meta"),
    priceNow: document.getElementById("priceNow"),
    liveDot: document.getElementById("liveDot"),
    orderCard: document.getElementById("orderCard"),
    needBadge: document.getElementById("needBadge"),
    summary: document.getElementById("summary"),
    orderList: document.getElementById("orderList"),
    how: document.getElementById("how"),
    analyticsCard: document.getElementById("analyticsCard"),
    biasText: document.getElementById("biasText"),
    analyticsBody: document.getElementById("analyticsBody"),
    analyticsNote: document.getElementById("analyticsNote"),
    priceChart: document.getElementById("priceChart"),
    cciChart: document.getElementById("cciChart"),
    cciPanel: document.getElementById("cciPanel"),
    cciToggle: document.getElementById("cciToggle"),
    chartReset: document.getElementById("chartReset"),
  };

  let priceChart, candleSeries;
  let upperEdge, lowerEdge;
  let cciChart, cciSeries, cciMaSeries;
  let channelData = [];
  let axisPriceLines = [];
  let overlayCanvas = null;
  let ready = false;
  /** null=未基线；之后只对新出现的开多/开空标记提醒 */
  let seenOpenKeys = null;
  let alertToastTimer = null;
  /** 跟随最新K线；用户拖动/缩放后为 false */
  let followRealtime = true;
  let applyingChartRange = false;
  let userGestureOnChart = false;

  const lightLayout = {
    background: { color: "#ffffff" },
    textColor: "#334155",
  };
  const lightGrid = {
    vertLines: { color: "#eef2f6" },
    horzLines: { color: "#eef2f6" },
  };

  function ensureOverlayCanvas() {
    if (overlayCanvas) return overlayCanvas;
    overlayCanvas = document.createElement("canvas");
    overlayCanvas.style.cssText =
      "position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:1;";
    el.priceChart.style.position = "relative";
    el.priceChart.appendChild(overlayCanvas);
    return overlayCanvas;
  }

  function drawChannelBand(ctx) {
    if (!channelData.length) return;
    const ts = priceChart.timeScale();
    let seg = null;
    const flush = () => {
      if (!seg || seg.pts.length < 2) {
        seg = null;
        return;
      }
      ctx.beginPath();
      for (let i = 0; i < seg.pts.length; i++) {
        const p = seg.pts[i];
        if (i === 0) ctx.moveTo(p.x, p.yu);
        else ctx.lineTo(p.x, p.yu);
      }
      for (let i = seg.pts.length - 1; i >= 0; i--) {
        const p = seg.pts[i];
        ctx.lineTo(p.x, p.yl);
      }
      ctx.closePath();
      ctx.fillStyle =
        seg.color === 1 ? "rgba(211,47,47,0.16)" : "rgba(46,125,50,0.16)";
      ctx.fill();
      ctx.strokeStyle =
        seg.color === 1 ? "rgba(211,47,47,0.55)" : "rgba(46,125,50,0.55)";
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      for (let i = 0; i < seg.pts.length; i++) {
        const p = seg.pts[i];
        if (i === 0) ctx.moveTo(p.x, p.yu);
        else ctx.lineTo(p.x, p.yu);
      }
      ctx.stroke();
      ctx.beginPath();
      for (let i = 0; i < seg.pts.length; i++) {
        const p = seg.pts[i];
        if (i === 0) ctx.moveTo(p.x, p.yl);
        else ctx.lineTo(p.x, p.yl);
      }
      ctx.stroke();
      seg = null;
    };

    for (const row of channelData) {
      if (row.upper == null || row.lower == null || !row.color) {
        flush();
        continue;
      }
      const x = ts.timeToCoordinate(row.time);
      const yu = candleSeries.priceToCoordinate(row.upper);
      const yl = candleSeries.priceToCoordinate(row.lower);
      if (x == null || yu == null || yl == null) {
        flush();
        continue;
      }
      if (!seg || seg.color !== row.color) {
        flush();
        seg = { color: row.color, pts: [] };
      }
      seg.pts.push({ x, yu, yl });
    }
    flush();
  }

  function drawOverlays() {
    if (!ready || !candleSeries) return;
    const canvas = ensureOverlayCanvas();
    const w = el.priceChart.clientWidth;
    const h = el.priceChart.clientHeight;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.floor(w * dpr));
    canvas.height = Math.max(1, Math.floor(h * dpr));
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    drawChannelBand(ctx);
  }

  function clearAxisPriceLines() {
    for (const pl of axisPriceLines) {
      try {
        candleSeries.removePriceLine(pl);
      } catch (_) {}
    }
    axisPriceLines = [];
  }

  /** 条件价只显示在右侧价轴标签，不在 K 线区画文字/短线 */
  function setAxisLevelLabels(status) {
    clearAxisPriceLines();
    if (!status || !candleSeries) return;
    const levels = [
      { title: "开仓", price: status.cond_entry, color: "#00838f" },
      { title: "止盈1", price: status.cond_tp1, color: "#ef5350" },
      { title: "止盈2", price: status.cond_tp2, color: "#e53935" },
      { title: "止盈3", price: status.cond_tp3, color: "#b71c1c" },
      { title: "止损", price: status.cond_sl, color: "#7b1fa2" },
    ];
    for (const lv of levels) {
      if (lv.price == null || Number.isNaN(Number(lv.price))) continue;
      const pl = candleSeries.createPriceLine({
        price: Number(lv.price),
        // 几乎看不见的横线，文字只出现在右侧价轴
        color: hexToRgba(lv.color, 0.08),
        lineWidth: 1,
        lineStyle: 2,
        axisLabelVisible: true,
        title: lv.title,
      });
      axisPriceLines.push(pl);
    }
  }

  function hexToRgba(hex, a) {
    const h = String(hex).replace("#", "");
    const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
    const n = parseInt(full, 16);
    const r = (n >> 16) & 255;
    const g = (n >> 8) & 255;
    const b = n & 255;
    return `rgba(${r},${g},${b},${a})`;
  }

  function initCharts() {
    priceChart = LightweightCharts.createChart(el.priceChart, {
      layout: lightLayout,
      grid: lightGrid,
      rightPriceScale: { borderColor: "#dbe3ec" },
      timeScale: {
        borderColor: "#dbe3ec",
        timeVisible: true,
        secondsVisible: false,
        // 允许把最后一根K线往左拖，右侧留白；默认也留一点边距
        rightOffset: 12,
        fixRightEdge: false,
        fixLeftEdge: false,
        lockVisibleTimeRangeOnResize: true,
        rightBarStaysOnScroll: false,
      },
      handleScroll: {
        mouseWheel: true,
        pressedMouseMove: true,
        horzTouchDrag: true,
        vertTouchDrag: true,
      },
      handleScale: {
        axisPressedMouseMove: true,
        mouseWheel: true,
        pinch: true,
      },
      crosshair: { mode: 0 },
    });

    // 上下轨细线作边缘；色带由 canvas 填充
    upperEdge = priceChart.addLineSeries({
      color: "rgba(120,144,156,0.35)",
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
    });
    lowerEdge = priceChart.addLineSeries({
      color: "rgba(120,144,156,0.35)",
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
    });

    candleSeries = priceChart.addCandlestickSeries({
      upColor: "#e53935",
      downColor: "#43a047",
      borderUpColor: "#c62828",
      borderDownColor: "#2e7d32",
      wickUpColor: "#c62828",
      wickDownColor: "#2e7d32",
    });

    cciChart = LightweightCharts.createChart(el.cciChart, {
      layout: lightLayout,
      grid: lightGrid,
      rightPriceScale: { borderColor: "#dbe3ec" },
      timeScale: {
        borderColor: "#dbe3ec",
        visible: false,
        rightOffset: 12,
        fixRightEdge: false,
        fixLeftEdge: false,
        lockVisibleTimeRangeOnResize: true,
        rightBarStaysOnScroll: false,
      },
    });
    cciSeries = cciChart.addLineSeries({
      color: "#334155",
      lineWidth: 2,
      priceLineVisible: false,
    });
    cciMaSeries = cciChart.addLineSeries({
      color: "#f9a825",
      lineWidth: 1,
      priceLineVisible: false,
    });

    const resize = () => {
      priceChart.applyOptions({
        width: el.priceChart.clientWidth,
        height: el.priceChart.clientHeight,
      });
      cciChart.applyOptions({
        width: el.cciChart.clientWidth,
        height: el.cciChart.clientHeight,
      });
      drawOverlays();
    };
    const ro = new ResizeObserver(resize);
    ro.observe(el.priceChart);
    ro.observe(el.cciChart);

    const onRange = (range) => {
      drawOverlays();
      syncCciRange(range);
      if (!applyingChartRange && userGestureOnChart) {
        setFollowRealtime(false);
      }
    };
    priceChart.timeScale().subscribeVisibleLogicalRangeChange(onRange);
    priceChart.subscribeCrosshairMove(drawOverlays);

    bindChartGestures(el.priceChart);
    bindChartGestures(el.cciChart);

    el.cciToggle.addEventListener("click", () => {
      const open = el.cciPanel.classList.toggle("collapsed") === false;
      el.cciToggle.setAttribute("aria-expanded", open ? "true" : "false");
      el.cciToggle.textContent = open ? "收起 CCI" : "CCI";
      requestAnimationFrame(resize);
    });

    if (el.chartReset) {
      el.chartReset.addEventListener("click", () => {
        resetChartView();
      });
    }
    updateResetBtn();

    ready = true;
  }

  function setSwipeEnabled(enabled) {
    if (window.ChannelBridge && window.ChannelBridge.setSwipeEnabled) {
      try {
        window.ChannelBridge.setSwipeEnabled(!!enabled);
      } catch (_) {}
    }
  }

  function bindChartGestures(node) {
    if (!node) return;
    const down = () => {
      userGestureOnChart = true;
      setSwipeEnabled(false);
    };
    const up = () => {
      setSwipeEnabled(true);
      // 稍延后清手势，避免 range 回调晚于 touchend
      setTimeout(() => {
        userGestureOnChart = false;
      }, 80);
    };
    node.addEventListener("touchstart", down, { passive: true });
    node.addEventListener("mousedown", down);
    node.addEventListener("wheel", () => {
      userGestureOnChart = true;
      setFollowRealtime(false);
    }, { passive: true });
    node.addEventListener("touchend", up, { passive: true });
    node.addEventListener("touchcancel", up, { passive: true });
    node.addEventListener("mouseup", up);
    node.addEventListener("mouseleave", up);
  }

  function syncCciRange(range) {
    if (!cciChart || !range) return;
    applyingChartRange = true;
    try {
      cciChart.timeScale().setVisibleLogicalRange(range);
    } catch (_) {
    } finally {
      applyingChartRange = false;
    }
  }

  function setFollowRealtime(on) {
    followRealtime = !!on;
    updateResetBtn();
  }

  function updateResetBtn() {
    if (!el.chartReset) return;
    el.chartReset.hidden = followRealtime;
    el.chartReset.classList.toggle("active", !followRealtime);
  }

  function resetChartView() {
    setFollowRealtime(true);
    applyingChartRange = true;
    try {
      if (candleSeries) {
        candleSeries.priceScale().applyOptions({ autoScale: true });
      }
      if (priceChart) {
        priceChart.timeScale().scrollToRealTime();
        const range = priceChart.timeScale().getVisibleLogicalRange();
        syncCciRange(range);
      }
    } catch (_) {
    } finally {
      applyingChartRange = false;
    }
    requestAnimationFrame(drawOverlays);
  }

  function toTime(s) {
    const t = Date.parse(String(s).replace(" ", "T"));
    return Math.floor(t / 1000);
  }

  function fullLine(times, values) {
    const out = [];
    for (let i = 0; i < times.length; i++) {
      const v = values[i];
      if (v == null || Number.isNaN(v)) continue;
      out.push({ time: toTime(times[i]), value: v });
    }
    return out;
  }

  function buildMarkers(b) {
    const markers = [];
    const n = b.time.length;
    for (let i = 0; i < n; i++) {
      const t = toTime(b.time[i]);
      if (b.open_long && b.open_long[i]) {
        markers.push({
          time: t,
          position: "belowBar",
          color: "#d32f2f",
          shape: "arrowUp",
          text: "开多",
        });
      }
      if (b.open_short && b.open_short[i]) {
        markers.push({
          time: t,
          position: "aboveBar",
          color: "#2e7d32",
          shape: "arrowDown",
          text: "开空",
        });
      }
      if (b.flat_long && b.flat_long[i]) {
        markers.push({
          time: t,
          position: "aboveBar",
          color: "#546e7a",
          shape: "circle",
          text: "平多",
        });
      }
      if (b.flat_short && b.flat_short[i]) {
        markers.push({
          time: t,
          position: "belowBar",
          color: "#546e7a",
          shape: "circle",
          text: "平空",
        });
      }
    }
    markers.sort((a, b2) => a.time - b2.time);
    return markers;
  }

  function collectOpenKeys(b) {
    const keys = [];
    if (!b || !b.time) return keys;
    for (let i = 0; i < b.time.length; i++) {
      const t = String(b.time[i]);
      if (b.open_long && b.open_long[i]) keys.push(t + "|L");
      if (b.open_short && b.open_short[i]) keys.push(t + "|S");
    }
    return keys;
  }

  function playBeep(kind) {
    try {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      const ctx = new Ctx();
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = "sine";
      o.frequency.value = kind === "long" ? 880 : 660;
      g.gain.value = 0.0001;
      o.connect(g);
      g.connect(ctx.destination);
      const now = ctx.currentTime;
      g.gain.exponentialRampToValueAtTime(0.18, now + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, now + 0.35);
      o.start(now);
      o.stop(now + 0.38);
      setTimeout(() => ctx.close(), 500);
    } catch (_) {}
  }

  function showAlertToast(kind, text) {
    const box = document.getElementById("alertToast");
    if (!box) return;
    box.hidden = false;
    box.className = "alert-toast " + (kind === "long" ? "long" : "short");
    box.textContent = text;
    if (alertToastTimer) clearTimeout(alertToastTimer);
    alertToastTimer = setTimeout(() => {
      box.hidden = true;
    }, 4500);
  }

  function fireOpenAlert(kind, symbol, barTime) {
    const label = kind === "long" ? "开多" : "开空";
    const title = label + "信号";
    const body = (symbol || "合约") + " K线出现「" + label + "」标记 · " + (barTime || "");
    showAlertToast(kind, title + " · " + (symbol || ""));
    if (window.ChannelBridge && window.ChannelBridge.notifyOpenSignal) {
      try {
        window.ChannelBridge.notifyOpenSignal(kind, title, body);
        return;
      } catch (_) {}
    }
    playBeep(kind);
    try {
      if (typeof Notification !== "undefined") {
        if (Notification.permission === "granted") {
          new Notification(title, { body: body, tag: "open-" + kind + "-" + barTime });
        } else if (Notification.permission === "default") {
          Notification.requestPermission().then((p) => {
            if (p === "granted") {
              new Notification(title, { body: body, tag: "open-" + kind + "-" + barTime });
            }
          });
        }
      }
    } catch (_) {}
  }

  function maybeAlertOpens(payload) {
    const b = payload && payload.bars;
    if (!b) return;
    const keys = collectOpenKeys(b);
    if (seenOpenKeys === null) {
      seenOpenKeys = new Set(keys);
      return;
    }
    const symbol = payload.symbol || "";
    for (const k of keys) {
      if (seenOpenKeys.has(k)) continue;
      seenOpenKeys.add(k);
      const kind = k.endsWith("|L") ? "long" : "short";
      const barTime = k.slice(0, -2);
      fireOpenAlert(kind, symbol, barTime);
    }
  }

  function roleClass(role) {
    if (role && String(role).startsWith("止盈")) return "tp";
    if (role === "止损") return "sl";
    if (role === "开多仓" || role === "开空仓" || role === "开仓") return "entry";
    return "entry";
  }

  function isEntryOrder(o) {
    return o && (o.role === "开多仓" || o.role === "开空仓" || o.role === "开仓");
  }

  function entryTitle(o) {
    if (o.direction) return o.direction;
    if (o.role === "开多仓" || o.side === "买开") return "开多仓";
    if (o.role === "开空仓" || o.side === "卖开") return "开空仓";
    return "开仓";
  }

  function renderOrder(status) {
    const need = !!status.need_order;
    el.orderCard.classList.toggle("yes", need);
    el.orderCard.classList.toggle("no", !need);
    el.orderCard.classList.remove("wait");
    el.needBadge.textContent = need ? "需要设条件单" : "无需设条件单";
    el.summary.textContent = status.summary || status.state || "";
    el.how.textContent = status.how || "";

    el.orderList.innerHTML = "";
    const orders = status.orders || [];
    if (!orders.length) {
      const empty = document.createElement("div");
      empty.className = "order-empty";
      empty.textContent = "保持观望，先别挂单";
      el.orderList.appendChild(empty);
      return;
    }

    for (const o of orders) {
      const div = document.createElement("div");
      div.className = "order-row " + roleClass(o.role);
      const price = o.price != null ? Number(o.price).toFixed(0) : "--";
      const distPts = o.distance_points != null ? `${o.distance_points}点` : "";
      const distAtr = o.distance_atr != null ? ` · ${o.distance_atr}ATR` : "";

      if (isEntryOrder(o)) {
        const title = entryTitle(o);
        div.className += " entry-main";
        div.innerHTML = `
          <div class="order-role">${title}</div>
          <div class="order-mid">
            <div class="order-cmp">开仓价格</div>
            <div class="order-price">${price}</div>
            <div class="order-dist">${o.op || ""} · ${o.lots != null ? o.lots + "手" : ""}${distPts ? " · " + distPts : ""}${distAtr}</div>
          </div>
          <div class="order-side">
            <div class="order-dir">${title}</div>
            <div class="order-lots">${o.lots != null ? o.lots + "手" : ""}</div>
          </div>
        `;
      } else {
        const opLine =
          (o.op || "") +
          (o.op ? " " : "") +
          (o.side || "");
        div.innerHTML = `
          <div class="order-role">${o.role || ""}</div>
          <div class="order-mid">
            <div class="order-cmp">${opLine}</div>
            <div class="order-price">${price}</div>
            <div class="order-dist">${distPts}${distAtr}</div>
          </div>
          <div class="order-side">
            <div class="order-dir">${o.side || ""}</div>
            <div class="order-lots">${o.lots != null ? o.lots + "手" : ""}</div>
          </div>
        `;
      }
      el.orderList.appendChild(div);
    }
  }

  function pct(v) {
    return v == null ? "--" : `${Number(v).toFixed(1)}%`;
  }

  function renderAnalytics(analytics) {
    el.analyticsCard.classList.remove("bias-profit", "bias-loss");
    if (!analytics || !analytics.available) {
      el.biasText.textContent = (analytics && analytics.bias_text) || "暂无条件单，无评估";
      el.analyticsBody.innerHTML = `<div class="order-empty">观望中，无需概率评估</div>`;
      el.analyticsNote.textContent = (analytics && analytics.note) || "";
      return;
    }

    const bias = analytics.bias || "none";
    if (bias === "profit") el.analyticsCard.classList.add("bias-profit");
    if (bias === "loss") el.analyticsCard.classList.add("bias-loss");
    el.biasText.textContent = analytics.bias_text || "机会评估";

    const fill = analytics.p_fill;
    const tp = analytics.p_tp_first;
    const sl = analytics.p_sl_first;
    const exp = analytics.expected_points;
    const expClass =
      exp == null ? "neutral" : exp > 0.5 ? "profit" : exp < -0.5 ? "loss" : "neutral";
    const expText =
      exp == null ? "--" : (exp > 0 ? "+" : "") + Number(exp).toFixed(1);

    el.analyticsBody.innerHTML = `
      <div class="metrics">
        <div class="metric">
          <div class="label">开仓触发</div>
          <div class="value">${pct(fill)}</div>
          <div class="bar"><i style="width:${Math.min(100, fill || 0)}%"></i></div>
        </div>
        <div class="metric tp">
          <div class="label">先止盈</div>
          <div class="value">${pct(tp)}</div>
          <div class="bar"><i style="width:${Math.min(100, tp || 0)}%"></i></div>
        </div>
        <div class="metric sl">
          <div class="label">先止损</div>
          <div class="value">${pct(sl)}</div>
          <div class="bar"><i style="width:${Math.min(100, sl || 0)}%"></i></div>
        </div>
      </div>
      <div class="expect-row ${expClass}">
        <div class="label">期望点数 · n=${analytics.sample_n || 0}</div>
        <div class="value">${expText}</div>
      </div>
    `;
    el.analyticsNote.textContent = analytics.note || "基于本合约样本内统计，非未来保证";
  }

  function renderCharts(payload) {
    if (!ready || !payload.bars) return;
    const b = payload.bars;
    const candles = [];
    channelData = [];
    const savedLogicalRange =
      !followRealtime && priceChart
        ? priceChart.timeScale().getVisibleLogicalRange()
        : null;

    for (let i = 0; i < b.time.length; i++) {
      const t = toTime(b.time[i]);
      candles.push({
        time: t,
        open: b.open[i],
        high: b.high[i],
        low: b.low[i],
        close: b.close[i],
      });
      channelData.push({
        time: t,
        upper: b.upper[i],
        lower: b.lower[i],
        color: b.color[i],
      });
    }
    candleSeries.setData(candles);
    candleSeries.setMarkers(buildMarkers(b));
    upperEdge.setData(fullLine(b.time, b.upper));
    lowerEdge.setData(fullLine(b.time, b.lower));

    setAxisLevelLabels(payload.status || {});

    const cci = [];
    const cciMa = [];
    for (let i = 0; i < b.time.length; i++) {
      const t = toTime(b.time[i]);
      if (b.cci[i] != null) cci.push({ time: t, value: b.cci[i] });
      if (b.cci_ma[i] != null) cciMa.push({ time: t, value: b.cci_ma[i] });
    }
    cciSeries.setData(cci);
    cciMaSeries.setData(cciMa);

    applyingChartRange = true;
    try {
      if (followRealtime) {
        priceChart.timeScale().scrollToRealTime();
      } else if (
        savedLogicalRange &&
        savedLogicalRange.from != null &&
        savedLogicalRange.to != null
      ) {
        // 用逻辑区间，才能保留右侧空白（to 可以大于最后一根下标）
        try {
          priceChart.timeScale().setVisibleLogicalRange(savedLogicalRange);
        } catch (_) {}
      }
      const range = priceChart.timeScale().getVisibleLogicalRange();
      if (range) cciChart.timeScale().setVisibleLogicalRange(range);
    } finally {
      applyingChartRange = false;
    }
    requestAnimationFrame(drawOverlays);
  }

  function applyPayload(payload) {
    if (!payload || !payload.status) return;
    const src = payload.source || "";
    el.liveDot.className =
      "dot " +
      (src === "live" || src === "device"
        ? "on"
        : src.startsWith("cache")
          ? "warn"
          : "off");
    const close = payload.status.close;
    el.priceNow.textContent = close != null ? Number(close).toFixed(0) : "—";
    el.meta.textContent = `${payload.symbol || ""} · ${payload.period || ""} · ${src}`;
    renderOrder(payload.status);
    renderAnalytics(payload.analytics);
    renderCharts(payload);
    maybeAlertOpens(payload);
  }

  /** Android 原生 DIFF 推送原始 K 线后，本地算策略并刷新 */
  function onRawBars(bars, meta) {
    if (!window.ChannelStrategy || !bars || !bars.length) return;
    if (meta && meta.symbol) {
      window.CHANNEL_CFG = window.CHANNEL_CFG || {};
      window.CHANNEL_CFG.symbol = meta.symbol;
    }
    try {
      const payload = window.ChannelStrategy.buildPayload(bars, 300);
      if (meta && meta.source) payload.source = meta.source;
      applyPayload(payload);
    } catch (e) {
      console.error(e);
      if (el.meta) el.meta.textContent = "策略计算失败";
    }
  }

  function onMdStatus(text) {
    if (el.meta) el.meta.textContent = text || "";
  }

  window.__onRawBars = onRawBars;
  window.__onMdStatus = onMdStatus;
  window.__applyPayload = applyPayload;

  initCharts();

  // 设备模式：等 Android 注入行情；若页面被电脑服务打开，仍可走旧 API
  const deviceMode =
    location.protocol === "file:" ||
    /android_asset/i.test(location.href) ||
    window.CHANNEL_DEVICE === true;

  if (deviceMode) {
    el.meta.textContent = "等待行情连接…";
    if (window.ChannelBridge && window.ChannelBridge.pageReady) {
      try {
        window.ChannelBridge.pageReady();
      } catch (_) {}
    }
  } else {
    function connectWs() {
      const proto = location.protocol === "https:" ? "wss" : "ws";
      const ws = new WebSocket(`${proto}://${location.host}/ws`);
      ws.onmessage = (ev) => {
        try {
          applyPayload(JSON.parse(ev.data));
        } catch (e) {
          console.error(e);
        }
      };
      ws.onclose = () => setTimeout(connectWs, 2000);
      ws.onopen = () => {
        setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) ws.send("ping");
        }, 15000);
      };
    }
    fetch("/api/snapshot")
      .then((r) => r.json())
      .then(applyPayload)
      .catch(console.error);
    connectWs();
  }
})();

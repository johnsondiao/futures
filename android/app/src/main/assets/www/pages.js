(() => {
  const DEFAULT_ALERT = {
    enabled: true,
    sound: true,
    vibrate: true,
    notification: true,
    toast: true,
    feishu_enabled: true,
    feishu_app_id: "",
    feishu_app_secret: "",
    feishu_open_id: "",
    feishu_state: "",
  };

  const LS_KEY = "channel_alert_settings_v1";
  let alertSettings = { ...DEFAULT_ALERT };
  const openStack = [];

  function loadLocal() {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (!raw) return;
      const o = JSON.parse(raw);
      alertSettings = { ...DEFAULT_ALERT, ...o };
    } catch (_) {}
  }

  function saveLocal() {
    try {
      localStorage.setItem(LS_KEY, JSON.stringify(alertSettings));
    } catch (_) {}
  }

  function syncFromNative() {
    if (!(window.ChannelBridge && window.ChannelBridge.getAlertSettings)) return;
    try {
      const raw = window.ChannelBridge.getAlertSettings();
      if (!raw) return;
      const o = typeof raw === "string" ? JSON.parse(raw) : raw;
      alertSettings = { ...DEFAULT_ALERT, ...o };
      saveLocal();
      paintSettings();
    } catch (_) {}
  }

  function pushNative() {
    saveLocal();
    if (window.ChannelBridge && window.ChannelBridge.setAlertSettings) {
      try {
        window.ChannelBridge.setAlertSettings(JSON.stringify(alertSettings));
      } catch (_) {}
    }
  }

  function paintSettings() {
    const map = {
      setAlertEnabled: "enabled",
      setAlertSound: "sound",
      setAlertVibrate: "vibrate",
      setAlertNotification: "notification",
      setAlertToast: "toast",
      setFeishuEnabled: "feishu_enabled",
    };
    for (const id of Object.keys(map)) {
      const el = document.getElementById(id);
      if (el) el.checked = !!alertSettings[map[id]];
    }
    const fieldMap = {
      setFeishuAppId: "feishu_app_id",
      setFeishuAppSecret: "feishu_app_secret",
    };
    for (const id of Object.keys(fieldMap)) {
      const el = document.getElementById(id);
      if (el && document.activeElement !== el) {
        el.value = alertSettings[fieldMap[id]] || "";
      }
    }

    const masterOn = !!alertSettings.enabled;
    for (const id of [
      "setAlertSound",
      "setAlertVibrate",
      "setAlertNotification",
      "setAlertToast",
      "setFeishuEnabled",
    ]) {
      const el = document.getElementById(id);
      if (el) el.disabled = !masterOn;
    }
    const feishuOn = masterOn && !!alertSettings.feishu_enabled;
    for (const id of ["setFeishuAppId", "setFeishuAppSecret"]) {
      const el = document.getElementById(id);
      if (el) el.disabled = !feishuOn;
    }
    const testBtn = document.getElementById("btnTestFeishu");
    if (testBtn) testBtn.disabled = !feishuOn;
    // 配对状态展示：长连接自动获取 open_id，无需手填
    const st = document.getElementById("feishuPairStatus");
    if (st) {
      const oid = alertSettings.feishu_open_id || "";
      const wsState = alertSettings.feishu_state || "";
      if (!feishuOn) {
        st.textContent = "飞书推送已关闭";
      } else if (oid) {
        st.textContent =
          "✅ 已配对（" + oid.slice(0, 14) + "…）" +
          (wsState ? " · 长连接：" + wsState : "");
      } else {
        st.textContent =
          "尚未配对：请在手机飞书里搜索你的机器人应用名，给它发一条任意消息即可自动获取" +
          (wsState ? "（长连接：" + wsState + "）" : "");
      }
    }
  }

  function readSettingsFromUi() {
    alertSettings = {
      enabled: !!(document.getElementById("setAlertEnabled") || {}).checked,
      sound: !!(document.getElementById("setAlertSound") || {}).checked,
      vibrate: !!(document.getElementById("setAlertVibrate") || {}).checked,
      notification: !!(document.getElementById("setAlertNotification") || {}).checked,
      toast: !!(document.getElementById("setAlertToast") || {}).checked,
      feishu_enabled: !!(document.getElementById("setFeishuEnabled") || {}).checked,
      feishu_app_id:
        (document.getElementById("setFeishuAppId") || {}).value?.trim() || "",
      feishu_app_secret:
        (document.getElementById("setFeishuAppSecret") || {}).value?.trim() || "",
      // open_id 由长连接自动配对写入，UI 不再手填，保留本机已有值
      feishu_open_id: alertSettings.feishu_open_id || "",
    };
    pushNative();
    paintSettings();
  }

  function showFeishuResult(msg, ok) {
    const box = document.getElementById("feishuToast");
    if (!box) {
      alert(msg);
      return;
    }
    box.hidden = false;
    box.className = "alert-toast feishu " + (ok ? "ok" : "bad");
    box.textContent = msg;
    setTimeout(() => {
      box.hidden = true;
    }, ok ? 4000 : 6000);
  }

  function openPanel(id) {
    const panel = document.getElementById(id);
    if (!panel) return;
    panel.hidden = false;
    document.body.classList.add("panel-open");
    // 禁用原生下拉刷新，避免 SwipeRefreshLayout 拦截面板内的向上滑动手势
    if (window.ChannelBridge && window.ChannelBridge.setSwipeEnabled) {
      try { window.ChannelBridge.setSwipeEnabled(false); } catch (_) {}
    }
    if (!openStack.includes(id)) openStack.push(id);
    if (id === "panelSettings") {
      syncFromNative();
      paintSettings();
    }
  }

  function closePanel(id) {
    const panel = document.getElementById(id);
    if (panel) panel.hidden = true;
    const i = openStack.lastIndexOf(id);
    if (i >= 0) openStack.splice(i, 1);
    if (!openStack.length) {
      document.body.classList.remove("panel-open");
      // 面板全部关闭后恢复下拉刷新
      if (window.ChannelBridge && window.ChannelBridge.setSwipeEnabled) {
        try { window.ChannelBridge.setSwipeEnabled(true); } catch (_) {}
      }
    }
  }

  function closeTopPanel() {
    if (!openStack.length) return false;
    closePanel(openStack[openStack.length - 1]);
    return true;
  }

  function getAlertSettings() {
    return { ...alertSettings };
  }

  function bindInputWithDebounce(id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener("input", () => {
      clearTimeout(el._t);
      el._t = setTimeout(readSettingsFromUi, 300);
    });
    // 失焦立即保存，避免用户切后台丢内容
    el.addEventListener("blur", readSettingsFromUi);
  }

  function initPages() {
    loadLocal();
    paintSettings();
    syncFromNative();

    const btnGuide = document.getElementById("btnGuide");
    const btnSettings = document.getElementById("btnSettings");
    if (btnGuide) btnGuide.addEventListener("click", () => openPanel("panelGuide"));
    if (btnSettings) btnSettings.addEventListener("click", () => openPanel("panelSettings"));

    document.querySelectorAll("[data-close]").forEach((btn) => {
      btn.addEventListener("click", () => closePanel(btn.getAttribute("data-close")));
    });

    [
      "setAlertEnabled",
      "setAlertSound",
      "setAlertVibrate",
      "setAlertNotification",
      "setAlertToast",
      "setFeishuEnabled",
    ].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener("change", readSettingsFromUi);
    });
    ["setFeishuAppId", "setFeishuAppSecret"].forEach(bindInputWithDebounce);

    const testBtn = document.getElementById("btnTestAlert");
    if (testBtn) {
      testBtn.addEventListener("click", () => {
        readSettingsFromUi();
        if (window.ChannelBridge && window.ChannelBridge.testOpenAlert) {
          try {
            window.ChannelBridge.testOpenAlert();
            const box = document.getElementById("alertToast");
            if (box) {
              box.hidden = false;
              box.className = "alert-toast long";
              box.textContent = "开多信号（试听）";
              setTimeout(() => {
                box.hidden = true;
              }, 4500);
            }
            return;
          } catch (_) {}
        }
        if (window.__testOpenAlert) window.__testOpenAlert();
      });
    }

    // 复制权限 JSON 按钮（飞书配置引导用）
    const copyPermsBtn = document.getElementById("btnCopyPermsJson");
    if (copyPermsBtn) {
      copyPermsBtn.addEventListener("click", () => {
        const jsonEl = document.getElementById("feishuPermsJson");
        if (!jsonEl) return;
        const text = jsonEl.textContent;
        const oldLabel = copyPermsBtn.textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(() => {
            copyPermsBtn.textContent = "✅ 已复制";
            setTimeout(() => { copyPermsBtn.textContent = oldLabel; }, 1500);
          }).catch(() => fallbackCopy(text, copyPermsBtn, oldLabel));
        } else {
          fallbackCopy(text, copyPermsBtn, oldLabel);
        }
      });
    }
    function fallbackCopy(text, btn, oldLabel) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); btn.textContent = "✅ 已复制"; } catch (_) {}
      document.body.removeChild(ta);
      setTimeout(() => { btn.textContent = oldLabel; }, 1500);
    }

    const feishuTestBtn = document.getElementById("btnTestFeishu");
    if (feishuTestBtn) {
      feishuTestBtn.addEventListener("click", () => {
        readSettingsFromUi();
        const appId = alertSettings.feishu_app_id;
        const appSecret = alertSettings.feishu_app_secret;
        const openId = alertSettings.feishu_open_id || "";
        if (!appId || !appSecret) {
          showFeishuResult("请先填写 App ID 和 App Secret", false);
          return;
        }
        if (!(window.ChannelBridge && window.ChannelBridge.testFeishuPush)) {
          showFeishuResult("当前版本不支持飞书推送，请更新 App", false);
          return;
        }
        feishuTestBtn.disabled = true;
        const oldLabel = feishuTestBtn.textContent;
        feishuTestBtn.textContent = "推送中…";
        const cbName =
          "__feishu_cb_" + Math.random().toString(36).slice(2) + "_" + Date.now();
        window[cbName] = function (o) {
          try {
            delete window[cbName];
          } catch (_) {
            window[cbName] = undefined;
          }
          feishuTestBtn.disabled = false;
          feishuTestBtn.textContent = oldLabel;
          showFeishuResult(o?.msg || "未知结果", !!o?.ok);
        };
        try {
          window.ChannelBridge.testFeishuPush(appId, appSecret, openId, cbName);
        } catch (e) {
          feishuTestBtn.disabled = false;
          feishuTestBtn.textContent = oldLabel;
          showFeishuResult("调用失败：" + (e.message || e), false);
        }
      });
    }
  }

  window.ChannelPages = {
    init: initPages,
    openPanel,
    closePanel,
    closeTopPanel,
    getAlertSettings,
  };
  window.__closeTopPanel = closeTopPanel;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initPages);
  } else {
    initPages();
  }
})();

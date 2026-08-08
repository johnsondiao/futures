(() => {
  const DEFAULT_ALERT = {
    enabled: true,
    sound: true,
    vibrate: true,
    notification: true,
    toast: true,
    feishu_enabled: true,
    feishu_webhook: "",
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
    const whEl = document.getElementById("setFeishuWebhook");
    if (whEl) whEl.value = alertSettings.feishu_webhook || "";

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
    if (whEl) whEl.disabled = !feishuOn;
    const testBtn = document.getElementById("btnTestFeishu");
    if (testBtn) testBtn.disabled = !feishuOn;
  }

  function readSettingsFromUi() {
    alertSettings = {
      enabled: !!(document.getElementById("setAlertEnabled") || {}).checked,
      sound: !!(document.getElementById("setAlertSound") || {}).checked,
      vibrate: !!(document.getElementById("setAlertVibrate") || {}).checked,
      notification: !!(document.getElementById("setAlertNotification") || {}).checked,
      toast: !!(document.getElementById("setAlertToast") || {}).checked,
      feishu_enabled: !!(document.getElementById("setFeishuEnabled") || {}).checked,
      feishu_webhook:
        (document.getElementById("setFeishuWebhook") || {}).value?.trim() || "",
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
    if (!openStack.length) document.body.classList.remove("panel-open");
  }

  function closeTopPanel() {
    if (!openStack.length) return false;
    closePanel(openStack[openStack.length - 1]);
    return true;
  }

  function getAlertSettings() {
    return { ...alertSettings };
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
    const whEl = document.getElementById("setFeishuWebhook");
    if (whEl) {
      whEl.addEventListener("input", () => {
        // 延迟一点保存，避免用户输入时被打断
        clearTimeout(whEl._t);
        whEl._t = setTimeout(readSettingsFromUi, 300);
      });
    }

    const testBtn = document.getElementById("btnTestAlert");
    if (testBtn) {
      testBtn.addEventListener("click", () => {
        readSettingsFromUi();
        // 原生试听更可靠（ToneGenerator + 震动 + 系统通知）
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

    const feishuTestBtn = document.getElementById("btnTestFeishu");
    if (feishuTestBtn) {
      feishuTestBtn.addEventListener("click", () => {
        readSettingsFromUi();
        const url = alertSettings.feishu_webhook;
        if (!url) {
          showFeishuResult("请先填写飞书机器人 Webhook URL", false);
          return;
        }
        if (!(window.ChannelBridge && window.ChannelBridge.testFeishuWebhook)) {
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
          window.ChannelBridge.testFeishuWebhook(url, cbName);
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

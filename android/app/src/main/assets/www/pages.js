(() => {
  const DEFAULT_ALERT = {
    enabled: true,
    sound: true,
    vibrate: true,
    notification: true,
    toast: true,
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
    };
    for (const id of Object.keys(map)) {
      const el = document.getElementById(id);
      if (el) el.checked = !!alertSettings[map[id]];
    }
    const masterOn = !!alertSettings.enabled;
    for (const id of [
      "setAlertSound",
      "setAlertVibrate",
      "setAlertNotification",
      "setAlertToast",
    ]) {
      const el = document.getElementById(id);
      if (el) el.disabled = !masterOn;
    }
  }

  function readSettingsFromUi() {
    alertSettings = {
      enabled: !!(document.getElementById("setAlertEnabled") || {}).checked,
      sound: !!(document.getElementById("setAlertSound") || {}).checked,
      vibrate: !!(document.getElementById("setAlertVibrate") || {}).checked,
      notification: !!(document.getElementById("setAlertNotification") || {}).checked,
      toast: !!(document.getElementById("setAlertToast") || {}).checked,
    };
    pushNative();
    paintSettings();
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
    ].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener("change", readSettingsFromUi);
    });

    const testBtn = document.getElementById("btnTestAlert");
    if (testBtn) {
      testBtn.addEventListener("click", () => {
        readSettingsFromUi();
        if (window.__testOpenAlert) window.__testOpenAlert();
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

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
    feishu_mobile: "",
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
      setFeishuMobile: "feishu_mobile",
    };
    for (const id of Object.keys(fieldMap)) {
      const el = document.getElementById(id);
      if (el && document.activeElement !== el) {
        el.value = alertSettings[fieldMap[id]] || "";
      }
    }

    // 绑定状态显示：已绑定时显示 open_id 前 16 位，未绑定时显示初始提示
    const statusEl = document.getElementById("openIdStatus");
    const bindBtn = document.getElementById("btnBindFeishuOpenId");
    if (statusEl) {
      const oid = alertSettings.feishu_open_id || "";
      if (oid && oid.startsWith("ou_")) {
        statusEl.innerHTML =
          '✅ <strong>已绑定</strong> Open ID：<code>' +
          oid.slice(0, 16) + '…</code>（手机号 ' +
          (alertSettings.feishu_mobile || "").replace(/(\d{3})\d+(\d{4})/, "$1****$2") +
          '）';
        statusEl.style.color = "#0f8a4b";
      } else {
        statusEl.innerHTML =
          '填入手机号后点上方按钮，App 会自动通过飞书接口查询对应的 open_id 并保存，<strong>无需手动复制 open_id</strong>。';
        statusEl.style.color = "";
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
    for (const id of ["setFeishuAppId", "setFeishuAppSecret", "setFeishuMobile"]) {
      const el = document.getElementById(id);
      if (el) el.disabled = !feishuOn;
    }
    if (bindBtn) bindBtn.disabled = !feishuOn;
    const testBtn = document.getElementById("btnTestFeishu");
    if (testBtn) testBtn.disabled = !feishuOn;
  }

  function readSettingsFromUi() {
    // 保留 open_id（由「点击绑定」按钮自动写入，不在 UI 上直接编辑）
    const prevOpenId = alertSettings.feishu_open_id || "";
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
      feishu_open_id: prevOpenId,
      feishu_mobile:
        (document.getElementById("setFeishuMobile") || {}).value?.trim() || "",
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
    ["setFeishuAppId", "setFeishuAppSecret", "setFeishuMobile"].forEach(bindInputWithDebounce);

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

    const feishuBindBtn = document.getElementById("btnBindFeishuOpenId");
    if (feishuBindBtn) {
      feishuBindBtn.addEventListener("click", () => {
        readSettingsFromUi();
        const appId = alertSettings.feishu_app_id;
        const appSecret = alertSettings.feishu_app_secret;
        const mobile = alertSettings.feishu_mobile;
        if (!appId || !appSecret) {
          showFeishuResult("请先填写 App ID 和 App Secret", false);
          return;
        }
        if (!mobile) {
          showFeishuResult("请先填写飞书账号绑定的手机号", false);
          return;
        }
        if (!(window.ChannelBridge && window.ChannelBridge.bindFeishuOpenId)) {
          showFeishuResult("当前版本不支持自动绑定，请更新 App", false);
          return;
        }
        feishuBindBtn.disabled = true;
        const oldLabel = feishuBindBtn.textContent;
        feishuBindBtn.textContent = "绑定中…";
        const cbName =
          "__feishu_bind_cb_" + Math.random().toString(36).slice(2) + "_" + Date.now();
        window[cbName] = function (o) {
          try {
            delete window[cbName];
          } catch (_) {
            window[cbName] = undefined;
          }
          feishuBindBtn.disabled = false;
          feishuBindBtn.textContent = oldLabel;
          if (o && o.openId) {
            // 自动绑定成功：写入 settings 并刷新状态显示
            alertSettings.feishu_open_id = o.openId;
            if (o.mobile) alertSettings.feishu_mobile = o.mobile;
            pushNative();
            paintSettings();
            showFeishuResult(
              "✅ Open ID 绑定成功：" + String(o.openId).slice(0, 16) + "…",
              true
            );
          } else {
            const msg = (o && o.errorMsg) || "绑定失败，请检查手机号或权限配置";
            showFeishuResult("❌ " + msg, false);
          }
        };
        try {
          window.ChannelBridge.bindFeishuOpenId(appId, appSecret, mobile, cbName);
        } catch (e) {
          feishuBindBtn.disabled = false;
          feishuBindBtn.textContent = oldLabel;
          showFeishuResult("调用失败：" + (e.message || e), false);
        }
      });
    }

    const feishuTestBtn = document.getElementById("btnTestFeishu");
    if (feishuTestBtn) {
      feishuTestBtn.addEventListener("click", () => {
        readSettingsFromUi();
        const appId = alertSettings.feishu_app_id;
        const appSecret = alertSettings.feishu_app_secret;
        const openId = alertSettings.feishu_open_id;
        if (!appId || !appSecret) {
          showFeishuResult("请先填写 App ID 和 App Secret", false);
          return;
        }
        if (!openId) {
          showFeishuResult("请先点击「绑定 Open ID」按钮完成自动绑定", false);
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

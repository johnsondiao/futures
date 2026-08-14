/**
 * Compare JS ChannelStrategy vs Python export.
 * Usage: node pc/scripts/compare_strategy.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath, pathToFileURL } from "url";
import vm from "vm";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const jsPath = path.join(root, "..", "android/app/src/main/assets/www/strategy.js");
const dataPath = path.join(root, "data/goal/strategy_compare_input.json");

const code = fs.readFileSync(jsPath, "utf8");
const sandbox = { window: {}, console };
vm.createContext(sandbox);
vm.runInContext(code, sandbox);
const CS = sandbox.window.ChannelStrategy;

const data = JSON.parse(fs.readFileSync(dataPath, "utf8"));
const payload = CS.buildPayload(data.bars, 300);
const st = payload.status;
const an = payload.analytics;
const py = data.py_status;
const pa = data.py_analytics;

function near(a, b, eps = 0.6) {
  if (a == null && b == null) return true;
  if (a == null || b == null) return false;
  return Math.abs(a - b) <= eps;
}

const checks = [
  ["state", st.state === py.state, st.state, py.state],
  ["need_order", st.need_order === py.need_order, st.need_order, py.need_order],
  ["close", near(st.close, py.close, 0.01), st.close, py.close],
  ["cond_entry", near(st.cond_entry, py.cond_entry), st.cond_entry, py.cond_entry],
  ["cond_tp1", near(st.cond_tp1, py.cond_tp1), st.cond_tp1, py.cond_tp1],
  ["cond_tp2", near(st.cond_tp2, py.cond_tp2), st.cond_tp2, py.cond_tp2],
  ["cond_tp3", near(st.cond_tp3, py.cond_tp3), st.cond_tp3, py.cond_tp3],
  ["cond_sl", near(st.cond_sl, py.cond_sl), st.cond_sl, py.cond_sl],
  ["orders_n", st.orders.length === py.orders_n, st.orders.length, py.orders_n],
  ["analytics.kind", an.kind === pa.kind, an.kind, pa.kind],
  ["analytics.available", an.available === pa.available, an.available, pa.available],
];

let fail = 0;
for (const [name, ok, js, pyv] of checks) {
  console.log((ok ? "OK " : "FAIL ") + name, "js=", js, "py=", pyv);
  if (!ok) fail += 1;
}
if (fail) {
  console.error("FAILED", fail);
  process.exit(1);
}
console.log("ALL OK");

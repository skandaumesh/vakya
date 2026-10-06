// Talks to the Vakya server for the content script and the settings page.
// Requests go from here (not from the WhatsApp page) so they aren't subject to the
// page's CORS rules, and so the app key never sits in the page.

try {
  importScripts("config.js");
} catch (e) {
  // No config.js (unbuilt copy): settings page values are used instead.
}
const CFG = self.VAKYA_CONFIG || {};
const TIMEOUT_MS = 45000; // a sleeping free server can take a while to wake up

async function settings() {
  const s = await chrome.storage.local.get(["serverUrl", "appKey", "ownGroqKey", "deviceId"]);
  let deviceId = s.deviceId;
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    await chrome.storage.local.set({ deviceId });
  }
  return {
    serverUrl: (s.serverUrl || CFG.serverUrl || "").replace(/\/+$/, ""),
    appKey: s.appKey || CFG.appKey || "",
    ownGroqKey: s.ownGroqKey || "",
    deviceId,
  };
}

async function call(path, body) {
  const s = await settings();
  if (!s.serverUrl) throw Object.assign(new Error("No server address set. Open Vakya settings."), { status: 0 });
  const headers = { "Content-Type": "application/json", "X-Vakya-Device": s.deviceId };
  if (s.appKey) headers["X-Vakya-Key"] = s.appKey;
  if (s.ownGroqKey) headers["X-Groq-Key"] = s.ownGroqKey;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let response;
  try {
    response = await fetch(s.serverUrl + path, {
      method: body ? "POST" : "GET",
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
  } catch (e) {
    const timeout = e.name === "AbortError";
    throw Object.assign(new Error(timeout ? "That took too long, try again." : "Can't reach the Vakya server."), { status: 0 });
  } finally {
    clearTimeout(timer);
  }
  const text = await response.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { detail: text.slice(0, 200) };
  }
  if (!response.ok) throw Object.assign(new Error(describe(response.status, data.detail)), { status: response.status });
  return data;
}

function describe(status, detail) {
  if (status === 401) return "Wrong app key. Check Vakya settings.";
  if (status === 429) return detail ? detail.charAt(0).toUpperCase() + detail.slice(1) : "Free AI limit reached. Try again in a minute.";
  if (status === 503) return `Server setup problem: ${detail || "unavailable"}`;
  return `Server error ${status}${detail ? `: ${detail}` : ""}`;
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    try {
      if (msg.type === "suggest") sendResponse({ ok: true, data: await call("/suggest", msg.body) });
      else if (msg.type === "compose") sendResponse({ ok: true, data: await call("/compose", msg.body) });
      else if (msg.type === "styleCard") sendResponse({ ok: true, data: await call("/style-card", msg.body) });
      else if (msg.type === "health") sendResponse({ ok: true, data: await call("/health") });
      else sendResponse({ ok: false, error: "unknown request" });
    } catch (e) {
      sendResponse({ ok: false, status: e.status || 0, error: e.message });
    }
  })();
  return true; // answer asynchronously
});

// The toolbar button opens settings in a tab (a popup would close when the file picker opens).
chrome.action.onClicked.addListener(() => chrome.runtime.openOptionsPage());

// Content scripts only start on page loads, so start Vakya in WhatsApp tabs that were
// already open when it was installed or updated (no need to press F5).
chrome.runtime.onInstalled.addListener(async () => {
  const tabs = await chrome.tabs.query({ url: "https://web.whatsapp.com/*" });
  for (const tab of tabs) {
    try {
      await chrome.scripting.insertCSS({ target: { tabId: tab.id }, files: ["content.css"] });
      await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["shared.js", "content.js"] });
    } catch (e) {
      console.warn("Vakya: couldn't start in an open WhatsApp tab", e);
    }
  }
});

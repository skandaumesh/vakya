// Vakya settings: connection check, own Groq key, style import, per-chat memory.
const { parseExport, senders, guessMe, contactFromFilename } = globalThis.VakyaShared;
const $ = (id) => document.getElementById(id);

function setStatus(id, text, ok) {
  const node = $(id);
  node.textContent = text;
  node.className = "status" + (ok === true ? " ok" : ok === false ? " bad" : "");
}

async function ask(type, body) {
  try {
    return await chrome.runtime.sendMessage({ type, body });
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

async function checkHealth() {
  setStatus("health", "Checking the Vakya server… (it can take up to a minute to wake up)");
  const res = await ask("health");
  if (res.ok) setStatus("health", "✅ Connected to the Vakya server.", true);
  else setStatus("health", "❌ " + res.error, false);
}

/** Ask the Vakya script in the WhatsApp Web tab what it can see (counts only, no message text). */
async function checkWhatsApp() {
  const details = $("check-details");
  details.hidden = true;
  const tabs = await chrome.tabs.query({ url: "https://web.whatsapp.com/*" });
  if (!tabs.length) {
    setStatus("check-status", "❌ No WhatsApp Web tab found. Open web.whatsapp.com in this browser (the same Chrome profile where you installed Vakya), then check again.", false);
    return;
  }
  let r;
  try {
    r = await chrome.tabs.sendMessage(tabs[0].id, { type: "vakyaCheck" });
  } catch {
    setStatus("check-status", "❌ Vakya isn't running in your WhatsApp tab yet. Press F5 in that tab, then check again.", false);
    return;
  }
  if (!r.box) setStatus("check-status", "❌ Vakya can't find WhatsApp's message box. Open a chat, then check again. If it still fails, send a screenshot of this page.", false);
  else if (!r.read) setStatus("check-status", "⚠️ Found the message box but no messages. Open a chat with messages. If it still fails, send a screenshot of this page.", false);
  else setStatus("check-status", `✅ Vakya sees the message box and ${r.read} messages${r.title ? "" : " (but not the chat name)"}. Look for the round button above the box, or press Alt+V.`, true);
  details.textContent = Object.entries(r).map(([k, v]) => `${k}: ${Array.isArray(v) ? "\n  " + v.join("\n  ") : v}`).join("\n");
  details.hidden = false;
}

$("check").addEventListener("click", checkWhatsApp);

// ---------- Style ----------

async function showStyle() {
  const { styleCard } = await chrome.storage.local.get("styleCard");
  if (!styleCard) return;
  const t = styleCard.traits || {};
  const words = (t.fillers || []).join(", ");
  $("style-summary").textContent = `${t.summary || ""}${t.language_mix ? "\nLanguage: " + t.language_mix : ""}${words ? "\nWords you use: " + words : ""}`;
  $("style-summary").style.whiteSpace = "pre-line";
}

let imported = null;

$("export-file").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const messages = parseExport(await file.text());
  if (!messages.length) {
    setStatus("style-status", "❌ That doesn't look like a WhatsApp chat export (.txt, without media).", false);
    return;
  }
  const contact = contactFromFilename(file.name);
  const me = guessMe(messages, contact);
  imported = { messages, me };
  const who = $("who");
  who.replaceChildren(...senders(messages).slice(0, 12).map((name) => {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name === me ? `${name} (you)` : name;
    return option;
  }));
  if (me) who.value = me;
  who.hidden = false;
  $("build").hidden = false;
  setStatus("style-status", me ? `Found ${messages.length} messages. Is "${me}" you?` : "Which of these is you?");
});

$("build").addEventListener("click", async () => {
  if (!imported) return;
  const me = $("who").value;
  const mine = imported.messages.filter((m) => m.sender === me).map((m) => m.text);
  if (mine.length < 5) {
    setStatus("style-status", `❌ Only ${mine.length} messages from ${me}. Pick a chat where you've written more.`, false);
    return;
  }
  const sample = mine.slice(-300);
  setStatus("style-status", `⏳ Learning your style from ${sample.length} messages… (a few seconds)`);
  $("build").disabled = true;
  const res = await ask("styleCard", { my_messages: sample });
  $("build").disabled = false;
  if (!res.ok) {
    setStatus("style-status", "❌ Couldn't update your style: " + res.error, false);
    return;
  }
  await chrome.storage.local.set({ styleCard: res.data });
  setStatus("style-status", `✅ Style updated from ${sample.length} messages.`, true);
  showStyle();
});

// ---------- Keys and server ----------

async function loadKeys() {
  const s = await chrome.storage.local.get(["ownGroqKey", "serverUrl", "appKey"]);
  $("groq-key").value = s.ownGroqKey || "";
  $("server-url").value = s.serverUrl || "";
  $("app-key").value = s.appKey || "";
}

$("save").addEventListener("click", async () => {
  await chrome.storage.local.set({
    ownGroqKey: $("groq-key").value.trim(),
    serverUrl: $("server-url").value.trim(),
    appKey: $("app-key").value.trim(),
  });
  setStatus("save-status", "Saved. Testing…");
  const res = await ask("health");
  setStatus("save-status", res.ok ? "✅ Saved and connected." : "❌ Saved, but: " + res.error, res.ok);
});

// ---------- Memory ----------

async function showChats() {
  const all = await chrome.storage.local.get(null);
  const chats = Object.entries(all).filter(([k]) => k.startsWith("chat:"));
  const box = $("chats");
  if (!chats.length) return;
  box.replaceChildren(...chats.map(([key, chat]) => {
    const item = document.createElement("div");
    item.className = "chat";
    const name = document.createElement("b");
    name.textContent = key.slice(5) + (chat.guessed ? ` · ${chat.guessed}` : "");
    item.append(name);
    const list = document.createElement("ul");
    for (const m of chat.memory || []) {
      const li = document.createElement("li");
      li.textContent = m;
      list.append(li);
    }
    if (!(chat.memory || []).length) {
      const li = document.createElement("li");
      li.textContent = "No notes yet";
      list.append(li);
    }
    const forget = document.createElement("button");
    forget.className = "secondary";
    forget.textContent = "Forget this chat";
    forget.addEventListener("click", async () => {
      await chrome.storage.local.remove(key);
      item.remove();
    });
    item.append(list, forget);
    return item;
  }));
}

checkHealth();
showStyle();
loadKeys();
showChats();

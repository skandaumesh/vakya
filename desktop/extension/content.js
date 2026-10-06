// Vakya on WhatsApp Web: a button above the message box (or Alt+V) opens a panel
// with 3 replies for the open chat; clicking one types it into WhatsApp's box.
// Everything here only reads the page and writes into the message box; it never sends.
(() => {
  "use strict";
  if (globalThis.vakyaRunning) return; // already injected into this tab (on install and by the manifest)
  globalThis.vakyaRunning = true;
  const { STYLES, LANGUAGES, applyMemory, isPlaceholder } = globalThis.VakyaShared;
  const LOGO = chrome.runtime.getURL("icons/icon128.png");
  const MAX_MESSAGES = 15;
  const MEDIA_MAX_SIDE = 384;

  let style = "mine";
  let button = null;
  let panel = null;
  let openTitle = null;
  let seq = 0;
  let lastResult = null;

  let panelTheme = "auto";
  const THEMES = ["white", "navy", "black", "rose", "mint", "glass"];

  chrome.storage.local.get(["style", "panelTheme"]).then((s) => {
    if (STYLES.some(([v]) => v === s.style)) style = s.style;
    if (THEMES.includes(s.panelTheme)) panelTheme = s.panelTheme;
  });
  // A new background picked in settings shows at once.
  chrome.storage.onChanged.addListener((changes) => {
    if (changes.panelTheme) panelTheme = changes.panelTheme.newValue || "auto";
  });

  // ---------- Reading WhatsApp Web ----------
  // WhatsApp Web changes its page often, so every lookup has a fallback that
  // doesn't depend on its ids or class names.

  const BUBBLES = ".message-in, .message-out";
  const STATUS = /^(online|typing(…|\.\.\.)?|last seen.*|click here.*|tap here.*)$/i;

  /** WhatsApp's message box. Fallback: the lowest wide text field in the bottom half of the
   *  window (the chat-list search box is at the top, so it's never picked). */
  function composeBox() {
    const known = document.querySelector('#main footer [contenteditable="true"]');
    if (known) return known;
    let best = null;
    let bestBottom = -1;
    for (const el of document.querySelectorAll('[contenteditable="true"]')) {
      if (el.closest(".vakya-panel")) continue;
      const r = el.getBoundingClientRect();
      if (r.width < 150 || !r.height || r.top < window.innerHeight * 0.5) continue;
      if (r.bottom > bestBottom) {
        best = el;
        bestBottom = r.bottom;
      }
    }
    return best;
  }

  /** The open chat: #main, else the message box's nearest ancestor that holds messages. */
  function chatPane(box = composeBox()) {
    const main = document.querySelector("#main");
    if (main) return main;
    for (let el = box?.parentElement; el && el !== document.body; el = el.parentElement) {
      if (el.querySelector(`${BUBBLES}, [data-pre-plain-text]`)) return el;
    }
    return null;
  }

  function chatTitle() {
    const header = chatPane()?.querySelector("header");
    for (const el of header ? header.querySelectorAll('span[dir="auto"], span[title]') : []) {
      const t = (el.getAttribute("title") || el.textContent || "").trim();
      if (t && !STATUS.test(t)) return t;
    }
    // Fallback: the highlighted chat in the chat list.
    const selected = document.querySelector('[aria-selected="true"] span[title], [aria-selected="true"] span[dir="auto"]');
    const t = selected ? (selected.getAttribute("title") || selected.textContent || "").trim() : "";
    return t || null;
  }

  /** Visible messages, oldest first: {sender: me|them, text, name, media, img}. */
  function readChat() {
    const pane = chatPane();
    if (!pane) return [];
    const paneRect = pane.getBoundingClientRect();
    let bubbles = [...pane.querySelectorAll(BUBBLES)].filter((b) => !b.parentElement.closest(BUBBLES));
    if (!bubbles.length) bubbles = [...pane.querySelectorAll("[data-pre-plain-text]")];
    const items = [];
    for (const bubble of bubbles) {
      // Who sent it: WhatsApp's in/out classes; else the message id ("true_..." = sent by me);
      // else the bubble's side of the screen.
      const msgId = (bubble.closest("[data-id]") || bubble.querySelector("[data-id]"))?.getAttribute("data-id") || "";
      let fromMe;
      if (bubble.classList.contains("message-out")) fromMe = true;
      else if (bubble.classList.contains("message-in")) fromMe = false;
      else if (/^true_/.test(msgId)) fromMe = true;
      else if (/^false_/.test(msgId)) fromMe = false;
      else {
        // Fallback if WhatsApp renames its classes: outgoing bubbles sit on the right.
        const r = bubble.getBoundingClientRect();
        if (!r.width) continue;
        fromMe = r.left - paneRect.left > paneRect.right - r.right;
      }
      // data-pre-plain-text = "[10:15, 05/10/2026] Rahul: " (sender and time).
      const copyable = bubble.matches("[data-pre-plain-text]") ? bubble : bubble.querySelector("[data-pre-plain-text]");
      const meta = copyable ? copyable.getAttribute("data-pre-plain-text") || "" : "";
      const nameMatch = /\]\s*(.+?):\s*$/.exec(meta);
      const textEl = copyable ? copyable.querySelector(".selectable-text") || copyable : null;
      const text = textEl ? textEl.innerText.trim() : "";
      const img = bubble.querySelector('img[src^="blob:"]');
      const isMedia = img && (img.naturalWidth || img.width) > 60;
      const media = isMedia ? (/sticker/i.test(img.alt || "") ? "sticker" : "photo") : null;
      if (!text && !media) continue;
      items.push({ sender: fromMe ? "me" : "them", text, name: nameMatch ? nameMatch[1].trim() : null, media, img: media ? img : null });
    }
    return items.slice(-MAX_MESSAGES);
  }

  /** A small JPEG of a photo/sticker (WhatsApp's blob: images are same-origin, so canvas can read them). */
  function toJpeg(img) {
    try {
      const w = img.naturalWidth;
      const h = img.naturalHeight;
      if (!w || !h) return null;
      const scale = Math.min(1, MEDIA_MAX_SIDE / Math.max(w, h));
      const canvas = document.createElement("canvas");
      canvas.width = Math.round(w * scale);
      canvas.height = Math.round(h * scale);
      const g = canvas.getContext("2d");
      g.fillStyle = "#ffffff";
      g.fillRect(0, 0, canvas.width, canvas.height);
      g.drawImage(img, 0, 0, canvas.width, canvas.height);
      return canvas.toDataURL("image/jpeg", 0.75).split(",")[1];
    } catch {
      return null;
    }
  }

  /** Put [text] in WhatsApp's message box, replacing what's there. Never presses Send. */
  function typeIntoBox(text) {
    const box = composeBox();
    if (!box) return false;
    box.focus();
    const range = document.createRange();
    range.selectNodeContents(box);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    if (!document.execCommand("insertText", false, text)) {
      const data = new DataTransfer();
      data.setData("text/plain", text);
      box.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
    }
    return true;
  }

  // ---------- Asking the server ----------

  const NOTHING_TO_REPLY =
    'Nothing on screen to reply to. Tip: type what you want to say in the box, or ask for something ("pickup line", "bday wish"), then press Alt+V.';

  /** Text in the box: write what I mean in every style. Otherwise (or with [forceReply]):
   *  replies to their newest messages, finishing anything typed. */
  let lastForceReply = false;

  async function suggest(forceReply = false) {
    lastForceReply = forceReply;
    const box = composeBox();
    if (!box || !panel) return;
    const title = chatTitle() || "Unknown chat";
    const items = readChat();
    const typed = box.innerText.trim();
    const draft = isPlaceholder(typed) ? "" : typed;
    const names = new Set(items.filter((m) => m.sender === "them" && m.name).map((m) => m.name));
    const isGroup = names.size > 1;
    const key = "chat:" + title;
    const stored = await chrome.storage.local.get([key, "styleCard"]);
    const chat = stored[key] || { memory: [] };
    const messages = (withImage) => items.map((m, i) => ({
      sender: m.sender,
      text: m.text.slice(0, 4000),
      name: isGroup && m.sender === "them" ? m.name : null,
      media: m.media,
      image: withImage === i ? toJpeg(m.img) : null,
    }));

    if (draft && !forceReply) {
      setMode("compose");
      const res = await ask("compose", {
        app: "whatsapp",
        chat_title: title,
        is_group: isGroup,
        messages: messages(-1),
        relationship: chat.relationship || chat.guessed || null,
        language: chat.language || "auto",
        style_card: stored.styleCard || null,
        examples: [],
        memory: chat.memory || [],
        intent: draft.slice(0, 1000),
      }, "Writing it in every style…", { 404: () => suggest(true) }); // older server: replies instead
      if (res) showCompose(res.data);
      return;
    }

    setMode("reply");
    if (!items.length) {
      showNote(NOTHING_TO_REPLY);
      return;
    }
    // My message is the last one: their older messages were already answered,
    // so these are follow-ups from me, never replies to my own text.
    const followUp = items[items.length - 1].sender === "me";

    // The newest photo/sticker they sent (in their latest turn, else among the last few).
    const lastMine = items.map((m) => m.sender).lastIndexOf("me");
    const theirMedia = items.map((m, i) => [m, i]).filter(([m]) => m.media && m.sender === "them");
    const target = theirMedia.filter(([, i]) => i > lastMine).pop() || theirMedia.filter(([, i]) => i >= items.length - 6).pop();

    const res = await ask("suggest", {
      app: "whatsapp",
      chat_title: title,
      is_group: isGroup,
      messages: messages(target ? target[1] : -1),
      relationship: chat.relationship || null,
      style,
      style_card: style === "mine" ? stored.styleCard || null : null,
      examples: [],
      memory: chat.memory || [],
      draft: draft.slice(0, 2000),
      language: chat.language || "auto",
    }, "Reading the chat…");
    if (!res) return;
    const r = res.data;
    chat.memory = applyMemory(chat.memory, r.memory_add, r.memory_resolve);
    chat.guessed = r.relationship_guess;
    await chrome.storage.local.set({ [key]: chat });
    showResult(r, followUp);
  }

  /** One request through the background script; null (with the reason shown) if it failed or is stale.
   *  [onStatus] maps an HTTP status to a handler that runs instead of showing the error. */
  async function ask(type, body, loading, onStatus = {}) {
    const mySeq = ++seq;
    showNote(loading);
    let res;
    try {
      res = await chrome.runtime.sendMessage({ type, body });
    } catch {
      res = { ok: false, error: "Vakya was updated. Reload WhatsApp Web (F5)." };
    }
    if (mySeq !== seq || !panel) return null;
    if (!res || !res.ok) {
      if (res && onStatus[res.status]) onStatus[res.status]();
      else showNote(res ? res.error : "Something went wrong.");
      return null;
    }
    return res;
  }

  // ---------- Button and panel ----------

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text; // never innerHTML: replies are untrusted text
    return e;
  }

  function keepFocus(e) {
    e.preventDefault(); // clicking Vakya must not take the cursor out of WhatsApp's box
  }

  /** WhatsApp's dark mode: body.dark, else a dark background behind the message box. */
  function isDark(box) {
    if (document.body.classList.contains("dark")) return true;
    for (let el = box; el; el = el.parentElement) {
      const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(getComputedStyle(el).backgroundColor);
      if (m && (m[4] === undefined || Number(m[4]) > 0.5)) return 0.299 * m[1] + 0.587 * m[2] + 0.114 * m[3] < 128;
    }
    return false;
  }

  /** The picked background, or for Auto: white, or navy in WhatsApp's dark mode. */
  function applyTheme(box) {
    const root = document.documentElement;
    for (const t of THEMES) root.classList.toggle("vakya-theme-" + t, panelTheme === t);
    root.classList.toggle("vakya-dark", !THEMES.includes(panelTheme) && isDark(box));
  }

  function refresh() {
    if (!chrome.runtime?.id) return shutDown(); // the extension was reloaded or removed
    const box = composeBox();
    applyTheme(box);
    if (!box) {
      button?.remove();
      button = null;
      closePanel();
      return;
    }
    if (!button) {
      button = el("button", "vakya-fab");
      button.type = "button";
      button.title = "Vakya: suggest replies (Alt+V)";
      const img = el("img");
      img.src = LOGO;
      img.alt = "Vakya";
      button.append(img);
      button.addEventListener("mousedown", keepFocus);
      button.addEventListener("click", togglePanel);
      document.body.append(button);
    }
    const r = box.getBoundingClientRect();
    button.style.left = `${Math.round(r.right - 46)}px`;
    button.style.top = `${Math.round(r.top - 54)}px`;
    button.style.display = panel ? "none" : "";
    if (panel) {
      if (chatTitle() !== openTitle) closePanel(); // switched chats
      else placePanel(r);
    }
  }

  function togglePanel() {
    if (panel) closePanel();
    else openPanel();
  }

  function openPanel() {
    if (!composeBox()) return;
    openTitle = chatTitle();
    lastResult = null;
    panel = el("div", "vakya-panel");
    const head = el("div", "vakya-head");
    const logo = el("img", "vakya-logo");
    logo.src = LOGO;
    const close = el("button", "vakya-close", "✕");
    close.type = "button";
    close.title = "Close (Esc)";
    close.addEventListener("click", closePanel);
    head.append(logo, el("div", "vakya-title", "Vakya"), close);

    const styles = el("div", "vakya-styles");
    styles.append(el("div", "vakya-label", "THEME / TONE"));
    const row = el("div", "vakya-style-row");
    for (const [value, label] of STYLES) {
      const pill = el("button", "vakya-pill", label);
      pill.type = "button";
      pill.dataset.style = value;
      pill.addEventListener("click", () => {
        style = value;
        chrome.storage.local.set({ style });
        markStyle();
        suggest(true); // the style menu only shows in reply mode
      });
      row.append(pill);
    }
    // In write-it-for-me mode: back to replies, which finish what's typed.
    const back = el("button", "vakya-pill vakya-back", "↩ Replies instead");
    back.type = "button";
    back.hidden = true;
    back.addEventListener("click", () => suggest(true));
    styles.append(row, back);

    // Reply language for this chat (remembered per chat): Auto follows the chat.
    const langLabel = el("div", "vakya-label vakya-lang-label", "LANGUAGE");
    const langRow = el("div", "vakya-style-row vakya-lang-row");
    for (const [value, label] of LANGUAGES) {
      const pill = el("button", "vakya-pill vakya-lang", label);
      pill.type = "button";
      pill.dataset.lang = value;
      pill.addEventListener("click", async () => {
        const key = "chat:" + (chatTitle() || "Unknown chat");
        const chat = (await chrome.storage.local.get(key))[key] || { memory: [] };
        chat.language = value === "auto" ? undefined : value;
        await chrome.storage.local.set({ [key]: chat });
        markLanguage(value);
        suggest(lastForceReply);
      });
      langRow.append(pill);
    }
    styles.append(langLabel, langRow);
    panel.append(head, styles, el("div", "vakya-body"), el("div", "vakya-foot"));
    panel.addEventListener("mousedown", (e) => {
      if (e.target.closest("button")) keepFocus(e);
    });
    document.body.append(panel);
    markStyle();
    const chatKey = "chat:" + (openTitle || "Unknown chat");
    chrome.storage.local.get(chatKey).then((s) => markLanguage((s[chatKey] || {}).language || "auto"));
    refresh();
    suggest();
  }

  function closePanel() {
    seq++;
    panel?.remove();
    panel = null;
    if (button) button.style.display = "";
  }

  function placePanel(boxRect) {
    const r = boxRect || composeBox()?.getBoundingClientRect();
    if (!r || !panel) return;
    const width = Math.min(Math.max(r.width + 80, 380), 560);
    panel.style.width = `${width}px`;
    panel.style.left = `${Math.round(Math.max(8, Math.min(r.right + 40 - width, window.innerWidth - width - 8)))}px`;
    panel.style.bottom = `${Math.round(window.innerHeight - r.top + 10)}px`;
  }

  function markLanguage(language) {
    panel?.querySelectorAll(".vakya-lang").forEach((p) => p.classList.toggle("is-on", p.dataset.lang === language));
  }

  function markStyle() {
    panel?.querySelectorAll(".vakya-pill").forEach((p) => p.classList.toggle("is-on", p.dataset.style === style));
  }

  function setBody(nodes, foot) {
    if (!panel) return;
    panel.querySelector(".vakya-body").replaceChildren(...nodes);
    const footEl = panel.querySelector(".vakya-foot");
    footEl.textContent = foot || "";
    footEl.style.display = foot ? "" : "none";
  }

  function showNote(text) {
    setBody([el("div", "vakya-note", text)], "");
  }

  /** Reply mode shows the style menu; write-it-for-me returns every style at once, so it hides it. */
  function setMode(mode) {
    if (!panel) return;
    const compose = mode === "compose";
    panel.querySelector(".vakya-label").textContent = compose ? "WRITE IT FOR ME · YOUR TEXT IN EVERY STYLE" : "THEME / TONE";
    panel.querySelector(".vakya-style-row:not(.vakya-lang-row)").hidden = compose;
    panel.querySelector(".vakya-back").hidden = !compose;
  }

  function cards(suggestions) {
    return suggestions.map((s, i) => {
      const card = el("button", "vakya-card");
      card.type = "button";
      card.title = `Use this (Alt+${i + 1})`;
      card.append(el("div", "vakya-card-label", s.label), el("div", "vakya-card-text", s.text));
      card.addEventListener("click", () => pick(i));
      return card;
    });
  }

  function showResult(r, followUp) {
    lastResult = r;
    const nodes = [];
    if (followUp) nodes.push(el("div", "vakya-meaning", "↪ Your message is the last one, so these are follow-ups."));
    if (r.meaning) nodes.push(el("div", "vakya-meaning", "💬 " + r.meaning));
    const intent = (r.intent || "").toLowerCase().replace(/_/g, " ");
    setBody(
      [...nodes, ...cards(r.suggestions)],
      [intent, r.language].filter(Boolean).join(" · ") +
        '\nTip: type an idea in the box (or ask: "pickup line", "roast him"), then press Alt+V.',
    );
  }

  /** Write it for me: my message in every style, or, if I asked for something ("pickup line"), 4 ideas. */
  function showCompose(r) {
    const ideas = r.kind === "ideas";
    const labels = Object.fromEntries(STYLES);
    const suggestions = ideas
      ? (r.ideas || []).map((i) => ({ label: i.label || "Idea", text: i.text }))
      : r.variants.filter((v) => labels[v.style]).map((v) => ({ label: labels[v.style], text: v.text }));
    lastResult = { suggestions };
    if (ideas && panel) panel.querySelector(".vakya-label").textContent = "IDEAS FOR YOU · TAP ONE TO USE IT";
    const nodes = r.meaning ? [el("div", "vakya-meaning", (ideas ? "💡 " : "✍️ ") + r.meaning)] : [];
    setBody([...nodes, ...cards(suggestions)], [r.language, "pick one to replace your text"].filter(Boolean).join(" · "));
  }

  function pick(i) {
    const s = lastResult?.suggestions?.[i];
    if (!s) return;
    typeIntoBox(s.text);
    closePanel();
  }

  // Alt+V opens/closes, Alt+1..4 picks a card, Esc closes.
  function onKey(e) {
    if (e.altKey && !e.ctrlKey && (e.key === "v" || e.key === "V")) {
      e.preventDefault();
      togglePanel();
    } else if (panel && e.altKey && ["1", "2", "3", "4"].includes(e.key)) {
      e.preventDefault();
      pick(Number(e.key) - 1);
    } else if (panel && e.key === "Escape") {
      closePanel();
    }
  }

  // Settings → "Check WhatsApp Web" asks what Vakya can see. Counts and positions only, never message text.
  function onCheck(msg, _sender, sendResponse) {
    if (msg?.type !== "vakyaCheck") return;
    const box = composeBox();
    const pane = chatPane(box);
    sendResponse({
      box: !!box,
      pane: pane ? (pane.id ? "#" + pane.id : pane.tagName.toLowerCase()) : null,
      title: !!chatTitle(),
      read: readChat().length,
      button: !!button?.isConnected,
      dark: isDark(box),
      main: !!document.querySelector("#main"),
      footer: !!document.querySelector("#main footer"),
      rows: document.querySelectorAll('[role="row"]').length,
      in: document.querySelectorAll(".message-in").length,
      out: document.querySelectorAll(".message-out").length,
      prePlain: document.querySelectorAll("[data-pre-plain-text]").length,
      editables: [...document.querySelectorAll('[contenteditable="true"]')].map((el) => {
        const r = el.getBoundingClientRect();
        return `${el.getAttribute("role") || "-"} tab=${el.dataset.tab || "-"} at ${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.width)}x${Math.round(r.height)}`;
      }),
      window: `${window.innerWidth}x${window.innerHeight}`,
    });
  }

  let timer = null;

  function shutDown() {
    clearInterval(timer);
    document.removeEventListener("keydown", onKey, true);
    window.removeEventListener("resize", refresh);
    closePanel();
    button?.remove();
    button = null;
  }

  // An older copy left in the page by an extension reload removes itself (see refresh);
  // clear its leftovers so there's only ever one button.
  document.querySelectorAll(".vakya-fab, .vakya-panel").forEach((n) => n.remove());
  document.addEventListener("keydown", onKey, true);
  window.addEventListener("resize", refresh);
  chrome.runtime.onMessage.addListener(onCheck);
  timer = setInterval(refresh, 700);
  refresh();
})();

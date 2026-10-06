// Logic shared by the WhatsApp Web script, the settings page and the tests.
// The export parser is a port of backend/app/chat_export.py (keep them in sync).
(function (root) {
  const STYLES = [
    ["mine", "My style"],
    ["professional", "Professional"],
    ["short", "Short"],
    ["genz", "Gen Z"],
  ];

  // 12/31/23, 9:15 PM - Name: text | 31/12/2023, 21:15 - Name: text | [31/12/23, 9:15:23 PM] Name: text
  const LINE = /^\[?(\d{1,4}[./-]\d{1,2}[./-]\d{1,4}),?\s+(\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[APap]\.?\s?[Mm]\.?)?)\]?\s*(?:-\s*)?(.*)$/;
  const SKIP = new Set([
    "<media omitted>", "this message was deleted", "you deleted this message",
    "null", "missed voice call", "missed video call",
  ]);
  const EDITED = "<This message was edited>";

  function normalise(line) {
    return line.replace(/[‎‏﻿]/g, "").replace(/[  ]/g, " ").replace(/\r$/, "");
  }

  /** WhatsApp "Export chat" text → [{sender, text}] (system lines and media dropped). */
  function parseExport(raw) {
    const parsed = [];
    let current = null;
    for (const rawLine of raw.split("\n")) {
      const line = normalise(rawLine);
      const m = LINE.exec(line);
      if (!m) {
        if (current) current.text += "\n" + line;
        continue;
      }
      const rest = m[3];
      const sep = rest.indexOf(": ");
      if (sep < 0) {
        current = null; // system line
        continue;
      }
      current = { sender: rest.slice(0, sep).replace(/^[~ ]+/, "").trim(), text: rest.slice(sep + 2) };
      parsed.push(current);
    }
    return parsed
      .map((m) => ({ sender: m.sender, text: m.text.split(EDITED).join("").trim() }))
      .filter(({ text }) => {
        const low = text.toLowerCase();
        return text && !SKIP.has(low) && !(low.endsWith(" omitted") && low.split(" ").length <= 2);
      });
  }

  function senders(messages) {
    const counts = new Map();
    for (const m of messages) counts.set(m.sender, (counts.get(m.sender) || 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([name]) => name);
  }

  /** In a 1:1 export, "me" is the sender who isn't the contact the file is named after. */
  function guessMe(messages, contact) {
    const names = senders(messages);
    if (names.length !== 2 || !contact) return null;
    const others = names.filter((n) => n.toLowerCase() !== contact.toLowerCase());
    return others.length === 1 ? others[0] : null;
  }

  function contactFromFilename(name) {
    const m = /WhatsApp Chat (?:with|-)\s*(.+?)(?:\s*\(\d+\))?\.txt$/i.exec(name || "");
    return m ? m[1].trim() : null;
  }

  /** Apply the server's memory updates for a chat (same rules as the phone app). */
  function applyMemory(memory, add, resolve, max = 30) {
    const out = (memory || []).filter((m) => !(resolve || []).includes(m));
    for (const item of add || []) if (item && !out.includes(item)) out.push(item);
    return out.slice(-max);
  }

  /** Grey placeholder text that some chat apps report as the box's content. */
  function isPlaceholder(text) {
    const t = (text || "").trim().toLowerCase().replace(/[.…]+$/, "");
    return ["message", "type a message", "write a message", "send a message", "text message"].includes(t);
  }

  const api = { STYLES, parseExport, senders, guessMe, contactFromFilename, applyMemory, isPlaceholder };
  root.VakyaShared = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);

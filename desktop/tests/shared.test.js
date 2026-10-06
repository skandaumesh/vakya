// Run: node --test desktop/tests
const test = require("node:test");
const assert = require("node:assert/strict");
const {
  STYLES, parseExport, senders, guessMe, contactFromFilename, applyMemory, isPlaceholder,
} = require("../extension/shared.js");

test("styles match the phone app's panel", () => {
  assert.deepEqual(STYLES.map(([v]) => v), ["mine", "professional", "short", "genz"]);
});

test("parses Android 12-hour exports with multi-line messages", () => {
  const raw = [
    "12/31/23, 9:15 PM - Messages and calls are end-to-end encrypted.",
    "12/31/23, 9:15 PM - Rahul: naale baruttiya?",
    "12/31/23, 9:16 PM - Skanda: haan maga",
    "bartini 10 ge",
    "12/31/23, 9:17 PM - Rahul: <Media omitted>",
    "12/31/23, 9:18 PM - Skanda: done <This message was edited>",
  ].join("\n");
  assert.deepEqual(parseExport(raw), [
    { sender: "Rahul", text: "naale baruttiya?" },
    { sender: "Skanda", text: "haan maga\nbartini 10 ge" },
    { sender: "Skanda", text: "done" },
  ]);
});

test("parses iPhone exports with brackets, seconds and invisible marks", () => {
  const raw = "[31/12/23, 9:15:23 PM] ~ Priya: ok sure\r\n‎[31/12/23, 9:16:01 PM] Me: image omitted\r\n";
  assert.deepEqual(parseExport(raw), [{ sender: "Priya", text: "ok sure" }]);
});

test("parses 24-hour exports", () => {
  assert.deepEqual(parseExport("31/12/2023, 21:15 - Client: invoice sent?"), [{ sender: "Client", text: "invoice sent?" }]);
});

test("returns nothing for text that isn't an export", () => {
  assert.deepEqual(parseExport("hello\nthis is a note"), []);
});

test("guesses me from the file name in a 1:1 chat", () => {
  const msgs = [
    { sender: "Rahul", text: "a" }, { sender: "Skanda", text: "b" }, { sender: "Skanda", text: "c" },
  ];
  assert.deepEqual(senders(msgs), ["Skanda", "Rahul"]);
  assert.equal(contactFromFilename("WhatsApp Chat with Rahul (2).txt"), "Rahul");
  assert.equal(contactFromFilename("WhatsApp Chat - Rahul.txt"), "Rahul");
  assert.equal(guessMe(msgs, "rahul"), "Skanda");
  assert.equal(guessMe(msgs, null), null);
  assert.equal(guessMe([...msgs, { sender: "Asha", text: "d" }], "Rahul"), null); // group: ask
});

test("applies memory updates without duplicates and keeps the newest 30", () => {
  assert.deepEqual(applyMemory(["a", "b"], ["c", "a", ""], ["b"]), ["a", "c"]);
  const many = Array.from({ length: 35 }, (_, i) => String(i));
  assert.equal(applyMemory([], many).length, 30);
  assert.equal(applyMemory([], many)[0], "5");
});

test("treats the box's grey hint as empty", () => {
  for (const t of ["Message", "Type a message", "type a message…", " Message. "]) assert.ok(isPlaceholder(t), t);
  for (const t of ["", "message me later", "ok"]) assert.ok(!isPlaceholder(t), t);
});

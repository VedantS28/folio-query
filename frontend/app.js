const elements = Object.fromEntries([
  "file-input", "drop-zone", "file-count", "upload-notice", "file-list",
  "clear-workspace", "connection", "hero", "starter", "suggestions", "messages", "composer",
  "question", "send", "composer-hint", "compare-versions", "compare-dialog", "compare-form",
  "compare-before", "compare-after", "compare-explain", "compare-error", "close-compare",
  "explain-option", "explain-label", "spreadsheet-example"
].map(id => [id, document.getElementById(id)]));

const apiBase = window.FOLIO_API_BASE || "http://127.0.0.1:8765";
const maxFiles = 8;
const maxBytes = 5 * 1024 * 1024;
const state = {
  files: [],
  previousQuestions: [],
  controller: new AbortController(),
  uploading: false,
  asking: false,
  modelProvider: "none"
};

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function icon(name) {
  const namespace = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(namespace, "svg");
  const use = document.createElementNS(namespace, "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  svg.setAttribute("aria-hidden", "true");
  return svg;
}

async function request(path, options = {}) {
  const response = await fetch(apiBase + path, { cache: "no-store", ...options });
  if (response.status === 413) {
    throw new Error("This request exceeds the host’s size limit. Try a smaller file or remove some files from the workspace.");
  }
  const result = await response.json().catch(() => null);
  if (!response.ok || !result) throw new Error(result?.error || "The server could not complete the request.");
  return result;
}

async function checkConnection() {
  const status = elements.connection;
  try {
    const response = await request("/api/status");
    state.modelProvider = response.modelProvider ?? (response.modelConfigured ? "local" : "none");
    const labels = { groq: "Groq AI", local: "Local AI", none: "Source search" };
    const descriptions = {
      groq: "Questions and selected passages are sent to Groq.",
      local: "Local AI configured. Source search is available as a fallback.",
      none: "Answers show matching passages from your files."
    };
    status.className = "connection online";
    status.replaceChildren(element("i"), document.createTextNode(labels[state.modelProvider]));
    status.title = descriptions[state.modelProvider];
  } catch {
    status.className = "connection offline";
    status.replaceChildren(element("i"), document.createTextNode("Backend offline"));
    status.title = "The Java backend is not responding.";
  }
}

function updateControls() {
  const hasFiles = state.files.length > 0;
  elements.send.disabled = !hasFiles || state.asking || state.uploading || !elements.question.value.trim();
  elements["clear-workspace"].disabled =
    !hasFiles && !state.uploading && !elements.messages.childElementCount && !elements.question.value
    && !elements["upload-notice"].textContent;
  elements["file-count"].textContent = state.files.length;
  elements["compare-versions"].hidden = state.files.length < 2 || !elements.hero.hidden;
  elements["compare-versions"].disabled = state.asking || state.uploading;
  elements["spreadsheet-example"].hidden = !state.files.some(entry => entry.document.sheets?.length);
  elements["spreadsheet-example"].disabled = state.asking || state.uploading;
  const hint = elements["composer-hint"];
  hint.classList.remove("error");
  if (state.uploading) hint.textContent = "Reading your files…";
  else if (state.asking) hint.textContent = state.modelProvider === "none" ? "Looking for matching passages…" : "Generating an answer…";
  else if (!hasFiles) hint.textContent = "Add a file to get started.";
  else hint.textContent = "Shift + Enter for a new line · Refresh clears this session.";
}

function renderFiles() {
  const rows = state.files.map(entry => {
    const row = element("li", "file-row");
    const type = element("span", "file-type", entry.document.format.slice(0, 5).toUpperCase());
    const details = element("span", "file-details");
    const name = element("strong", "", entry.document.name);
    name.title = entry.document.name;
    const size = Math.max(1, Math.round(entry.size / 1024)) + " KiB";
    details.append(name, element("small", "", entry.document.truncated ? size + " · Text truncated" : size));
    const remove = element("button", "remove-file");
    remove.type = "button";
    remove.title = "Remove " + entry.document.name;
    remove.setAttribute("aria-label", remove.title);
    remove.append(icon("close"));
    remove.addEventListener("click", () => {
      state.files = state.files.filter(file => file !== entry);
      state.previousQuestions = [];
      renderFiles();
      elements["drop-zone"].focus();
    });
    row.append(type, details, remove);
    return row;
  });
  elements["file-list"].replaceChildren(...rows);
  updateControls();
}

async function upload(files) {
  if (!files.length) return;
  const notice = elements["upload-notice"];
  if (state.uploading) {
    notice.textContent = "Wait for the current upload to finish.";
    return;
  }
  const controller = state.controller;
  state.uploading = true;
  notice.textContent = "";
  const errors = [];
  if (files.length > maxFiles) errors.push("Only the first 8 selected files were considered.");
  updateControls();
  for (const file of files.slice(0, maxFiles)) {
    if (controller.signal.aborted) return;
    if (state.files.length === maxFiles) {
      errors.push("The workspace holds up to 8 files. Remove a file to add another.");
      break;
    }
    if (file.size > maxBytes) {
      errors.push(file.name + " is larger than 5 MiB.");
      continue;
    }
    if (state.files.some(entry => entry.key === fileKey(file))) {
      errors.push(file.name + " is already in this workspace.");
      continue;
    }
    try {
      const document = await request("/api/extract?name=" + encodeURIComponent(file.name), {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: file,
        signal: controller.signal
      });
      if (controller.signal.aborted) return;
      state.files.push({ document, size: file.size, key: fileKey(file) });
      state.previousQuestions = [];
      renderFiles();
    } catch (error) {
      if (controller.signal.aborted) return;
      errors.push(file.name + " — " + error.message);
    }
  }
  state.uploading = false;
  notice.textContent = errors.join("\n");
  renderFiles();
}

function fileKey(file) {
  return [file.name, file.size, file.lastModified].join("|");
}

function renderMessage(role, text) {
  elements.hero.hidden = true;
  elements.starter.hidden = true;
  elements.messages.hidden = false;
  const article = element("article", "message " + role);
  article.setAttribute("aria-label", role === "user" ? "Your question" : "Folio answer");
  const avatar = element("div", "avatar", role === "user" ? "You" : undefined);
  avatar.setAttribute("aria-hidden", "true");
  if (role !== "user") avatar.append(icon("spark"));
  const bubble = element("div", "bubble");
  bubble.append(element("p", "", text));
  article.append(avatar, bubble);
  elements.messages.append(article);
  return { article, bubble };
}

function resizeQuestion() {
  const field = elements.question;
  field.style.height = "auto";
  field.style.height = Math.min(145, field.scrollHeight) + "px";
}

function scrollToComposer() {
  requestAnimationFrame(() => elements.composer.scrollIntoView({ block: "end" }));
}

async function ask() {
  const question = elements.question.value.trim();
  if (!question || state.asking || state.uploading || !state.files.length) return;
  const controller = state.controller;
  const documents = state.files.map(entry => entry.document);
  const fileSnapshot = state.files.slice();
  const previousQuestions = state.previousQuestions.slice(-2);
  state.asking = true;
  renderMessage("user", question);
  elements.question.value = "";
  resizeQuestion();
  const pending = renderMessage("assistant", "Looking through your sources…");
  updateControls();
  scrollToComposer();
  try {
    const answer = await request("/api/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question, documents, previousQuestions }),
      signal: controller.signal
    });
    if (controller.signal.aborted) return;
    if (state.files.length === fileSnapshot.length && state.files.every((file, index) => file === fileSnapshot[index])) {
      state.previousQuestions = [...previousQuestions, question].slice(-2);
    }
    const title = { "groq-ai": "Groq AI answer", spreadsheet: "Spreadsheet calculation", "local-ai": "Local AI answer", excerpts: "Source passages", "no-match": "No matching passages" };
    pending.bubble.replaceChildren(element("span", "mode", title[answer.mode]), element("p", "", answer.text));
    answer.sources.forEach((source, index) => {
      const details = element("details", "evidence");
      details.open = answer.mode === "spreadsheet" || (answer.mode === "excerpts" && index < 2);
      const label = "[" + (index + 1) + "] " + source.file + " · " + source.location;
      details.append(element("summary", "", label), element("pre", "", source.excerpt));
      pending.bubble.append(details);
    });
  } catch (error) {
    if (controller.signal.aborted) return;
    pending.article.classList.add("message-error");
    pending.bubble.replaceChildren(element("p", "", error.message));
    if (!elements.question.value) elements.question.value = question;
    resizeQuestion();
  } finally {
    if (!controller.signal.aborted) {
      state.asking = false;
      updateControls();
      scrollToComposer();
    }
  }
}

function openComparison() {
  if (state.asking || state.uploading) return;
  if (state.files.length < 2) {
    elements["composer-hint"].textContent = "Add an earlier and a revised file to compare them.";
    elements["composer-hint"].classList.add("error");
    elements["drop-zone"].focus();
    return;
  }
  const before = elements["compare-before"];
  const after = elements["compare-after"];
  for (const select of [before, after]) {
    select.replaceChildren(...state.files.map((entry, index) => new Option(entry.document.name, index)));
  }
  after.selectedIndex = 1;
  elements["compare-error"].textContent = "";
  elements["compare-explain"].checked = false;
  elements["explain-option"].hidden = state.modelProvider === "none";
  elements["explain-label"].textContent = state.modelProvider === "groq"
    ? "Send sample changes to Groq for an explanation"
    : "Explain a sample of changes with the local model";
  elements["compare-dialog"].showModal();
}

async function compareVersions(event) {
  event.preventDefault();
  if (state.asking || state.uploading) return;
  const before = state.files[elements["compare-before"].selectedIndex]?.document;
  const after = state.files[elements["compare-after"].selectedIndex]?.document;
  if (!before || !after || before === after) {
    elements["compare-error"].textContent = "Choose two different files.";
    return;
  }
  const explain = elements["compare-explain"].checked;
  elements["compare-dialog"].close();
  const controller = state.controller;
  state.asking = true;
  renderMessage("user", "Compare " + before.name + " → " + after.name);
  const pending = renderMessage("assistant", "Comparing passages…");
  pending.article.classList.add("comparison-message");
  updateControls();
  try {
    const report = await request("/api/compare", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ before, after, explain }),
      signal: controller.signal
    });
    if (controller.signal.aborted) return;
    showComparison(pending.bubble, report, explain);
  } catch (error) {
    if (!controller.signal.aborted) {
      pending.article.classList.add("message-error");
      pending.bubble.replaceChildren(element("p", "", error.message));
    }
  } finally {
    if (!controller.signal.aborted) {
      state.asking = false;
      updateControls();
      pending.article.scrollIntoView({ block: "start" });
    }
  }
}

function showComparison(bubble, report, explain) {
  bubble.replaceChildren(element("h2", "comparison-title", "Version comparison"));
  const labels = { added: "Added", modified: "Edited", removed: "Removed", moved: "Moved" };
  const counts = element("div", "change-counts");
  for (const [kind, label] of Object.entries(labels)) {
    counts.append(element("span", "change-kind " + kind, report[kind] + " " + label.toLowerCase()));
  }
  bubble.append(counts);
  if (report.explanation) {
    bubble.append(element("h3", "comparison-explanation", "AI explanation of sample changes"),
      element("p", "", report.explanation.text));
    const evidence = element("details", "evidence");
    evidence.append(element("summary", "", "Passages sent for explanation"));
    report.explanation.sources.forEach((source, index) => {
      evidence.append(element("pre", "", "[" + (index + 1) + "] " + source.file + " · " + source.location + "\n" + source.excerpt));
    });
    bubble.append(evidence);
  } else if (explain && report.changes.length) {
    bubble.append(element("p", "comparison-explanation", "AI explanation unavailable. Review the passages below."));
  }
  if (!report.changes.length) bubble.append(element("p", "", "No changes in the extracted text."));
  report.changes.forEach((change, index) => {
    const card = element("details", "change-card");
    card.open = index < 3;
    const where = change.after?.location ?? change.before.location;
    const label = labels[change.kind] + (change.kind === "modified" && change.reordered ? " and moved" : "");
    const title = element("summary", "", label + " · " + where);
    const pair = element("div", "change-pair");
    const left = change.before?.text ?? "";
    const right = change.after?.text ?? "";
    const highlights = change.kind === "modified" ? compareWords(left, right) : null;
    pair.append(changeSide("Earlier", report.before, change.before, "removed", highlights?.[0], change.kind),
      changeSide("Revised", report.after, change.after, "added", highlights?.[1], change.kind));
    card.append(title, pair);
    bubble.append(card);
  });
}

function compareWords(before, after) {
  const left = before.match(/\s+|[^\s]+/g) ?? [];
  const right = after.match(/\s+|[^\s]+/g) ?? [];
  let start = 0;
  let end = 0;
  const limit = Math.min(left.length, right.length);
  while (start < limit && left[start] === right[start]) start++;
  while (start + end < limit && left[left.length - 1 - end] === right[right.length - 1 - end]) end++;
  return [left, right].map(tokens => [
    tokens.slice(0, start).join(""),
    tokens.slice(start, tokens.length - end).join(""),
    tokens.slice(tokens.length - end).join("")
  ]);
}

function changeSide(label, file, passage, tone, highlight, kind) {
  const side = element("div", "change-side");
  side.append(element("strong", "", label + " · " + file));
  if (!passage) {
    side.append(element("p", "missing-passage", "No corresponding passage"));
    return side;
  }
  side.append(element("small", "", passage.location));
  const text = element("pre");
  if (highlight) {
    text.append(highlight[0], element("mark", tone, highlight[1]), highlight[2]);
  } else if (kind === tone) {
    text.append(element("mark", tone, passage.text));
  } else {
    text.textContent = passage.text;
  }
  side.append(text);
  return side;
}

function suggestSpreadsheetQuestion() {
  const book = state.files.find(entry => entry.document.sheets?.length)?.document;
  if (!book) return;
  const sheet = book.sheets[0];
  const [headers, ...rows] = sheet.rows;
  const entries = Object.entries(headers.cells);
  const numeric = entries.find(([column]) => rows.some(row => row.cells[column]?.number != null));
  const prompt = numeric ? "Sum " + numeric[1].text : "Count rows";
  elements.question.value = prompt + " in " + sheet.name;
  elements.question.focus();
  resizeQuestion();
  updateControls();
}

function clearWorkspace() {
  if (elements["compare-dialog"].open) elements["compare-dialog"].close();
  state.controller.abort();
  state.controller = new AbortController();
  state.files = [];
  state.previousQuestions = [];
  state.asking = false;
  state.uploading = false;
  elements["upload-notice"].textContent = "";
  elements["file-input"].value = "";
  elements.question.value = "";
  elements.messages.replaceChildren();
  elements.messages.hidden = true;
  elements.hero.hidden = false;
  elements.starter.hidden = false;
  resizeQuestion();
  renderFiles();
  window.scrollTo(0, 0);
}

const fileInput = elements["file-input"];
const dropZone = elements["drop-zone"];
dropZone.addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", () => {
  const files = Array.from(fileInput.files);
  fileInput.value = "";
  upload(files);
});
dropZone.addEventListener("dragover", event => {
  event.preventDefault();
  event.dataTransfer.dropEffect = "copy";
  dropZone.classList.add("dragging");
});
dropZone.addEventListener("dragleave", () => dropZone.classList.remove("dragging"));
dropZone.addEventListener("drop", event => {
  event.preventDefault();
  dropZone.classList.remove("dragging");
  upload(Array.from(event.dataTransfer.files));
});
window.addEventListener("dragover", event => {
  if (event.dataTransfer.types.includes("Files")) event.preventDefault();
});
window.addEventListener("drop", event => {
  if (event.dataTransfer.types.includes("Files")) event.preventDefault();
});
elements["clear-workspace"].addEventListener("click", () => {
  clearWorkspace();
  elements["drop-zone"].focus();
});
elements["compare-versions"].addEventListener("click", openComparison);
elements["compare-form"].addEventListener("submit", compareVersions);
elements["close-compare"].addEventListener("click", () => elements["compare-dialog"].close());
elements["spreadsheet-example"].addEventListener("click", suggestSpreadsheetQuestion);
elements.suggestions.addEventListener("click", event => {
  if (event.target.closest("[data-compare]")) {
    openComparison();
    return;
  }
  const button = event.target.closest("[data-question]");
  if (!button) return;
  elements.question.value = button.dataset.question;
  elements.question.focus();
  resizeQuestion();
  updateControls();
  if (state.files.length) ask();
  else {
    elements["composer-hint"].textContent = "Add a file, then send this question.";
    elements["composer-hint"].classList.add("error");
  }
});
elements.composer.addEventListener("submit", event => {
  event.preventDefault();
  ask();
});
elements.question.addEventListener("input", () => {
  resizeQuestion();
  updateControls();
});
elements.question.addEventListener("keydown", event => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    ask();
  }
});
window.addEventListener("pageshow", event => {
  if (event.persisted) clearWorkspace();
});
checkConnection();

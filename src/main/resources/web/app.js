"use strict";
// Everything user- or repository-derived is inserted with textContent, never innerHTML.

const $ = (id) => document.getElementById(id);
const SVGNS = "http://www.w3.org/2000/svg";

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return e;
}
function svg(tag, attrs, ...kids) {
  const e = document.createElementNS(SVGNS, tag);
  for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return e;
}
async function api(path, opts) {
  opts = opts || {};
  const post = opts.method === "POST";
  const send = () => {
    const headers = Object.assign({}, opts.headers);
    if (post) headers["content-type"] = "application/json";
    let key = null;
    try { key = sessionStorage.getItem("repairKey"); } catch (e) { /* storage may be unavailable */ }
    if (key) headers["x-api-key"] = key;
    return fetch(path, Object.assign({}, opts, { headers, body: post && opts.body === undefined ? "{}" : opts.body }));
  };
  let r = await send();
  if (r.status === 401 && post) {
    const k = window.prompt("This server requires an API key (REPAIR_API_KEY):");
    if (k) {
      try { sessionStorage.setItem("repairKey", k); } catch (e) { /* ignore */ }
      r = await send();
    }
  }
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.error || body.message || r.statusText);
  return body;
}
const fmtMs = (ms) => ms < 1000 ? ms + " ms" : (ms / 1000).toFixed(1) + " s";
const pct = (x) => (100 * x).toFixed(0) + "%";
const usd = (x) => x < 0.01 ? "$" + x.toFixed(4) : "$" + x.toFixed(2);

let selected = null;
let lastRun = null;
let approving = false;

// ----------------------------------------------------------------------------------------------- metrics + list
async function refreshMetrics() {
  try {
    const m = await api("/api/metrics");
    const box = $("metrics");
    box.replaceChildren(
      el("span", null, "runs ", el("b", null, m.finished + "/" + m.runs)),
      el("span", null, "resolved ", el("b", null, pct(m.resolveRate))),
      el("span", null, "latency p50/p95 ", el("b", null, fmtMs(m.latencyP50Ms) + " / " + fmtMs(m.latencyP95Ms))),
      el("span", null, "fixed within 1/3/10 candidates ", el("b", null, pct(m.solvedWithin1) + " / " + pct(m.solvedWithin3) + " / " + pct(m.solvedWithin10))),
      el("span", null, "tokens ", el("b", null, (m.inputTokens + m.outputTokens).toLocaleString())),
      el("span", null, "cost ", el("b", null, usd(m.costUsd))),
      el("span", null, "queue ", el("b", null, m.running + " running, " + m.queued + " waiting")));
  } catch (e) { /* server restarting; next tick retries */ }
}

async function refreshList() {
  try {
    const runs = await api("/api/runs");
    $("noRuns").hidden = runs.length > 0;
    $("runs").replaceChildren(...runs.map((r) => el("li", null,
      el("button", { type: "button", "aria-current": r.id === selected ? "true" : "false", onclick: () => select(r.id) },
        el("span", { class: "t" }, r.label),
        el("span", { class: "m" },
          el("span", { class: "badge " + (r.finished ? r.state : "live") + (r.finished ? "" : " pulse") }, r.finished ? r.state.toLowerCase() : r.state.toLowerCase() + "..."),
          el("span", null, r.failing + " failing"),
          el("span", null, fmtMs(r.latencyMs)))))));
    if (!selected && runs.length) select(runs[0].id);
  } catch (e) { /* ignore */ }
}

function select(id) {
  selected = id;
  lastRun = null;
  refreshList();
  refreshRun();
}

async function refreshRun() {
  if (!selected) return;
  try {
    const run = await api("/api/runs/" + selected);
    if (run.id !== selected) return;
    const sig = JSON.stringify([run.state, run.events.length, run.attempts.length, run.approved, run.prUrl, run.suspects.length, run.diff.length]);
    if (lastRun === sig && run.finished) return;
    lastRun = sig;
    renderRun(run);
  } catch (e) {
    $("main").replaceChildren(el("p", { class: "err" }, e.message));
  }
}

// --------------------------------------------------------------------------------------------- state machine
const NODES = {
  INGEST: [70, 60], LOCALIZE: [215, 60], SYNTHESIZE: [360, 60], VERIFY: [505, 60], PUBLISH: [650, 60], RESOLVED: [790, 60],
  CRITIC: [432, 170], UNRESOLVED: [432, 262],
};
const LABEL = { INGEST: "Ingest", LOCALIZE: "Localize", SYNTHESIZE: "Synthesize", VERIFY: "Verify", PUBLISH: "Publish", RESOLVED: "Resolved", CRITIC: "Critic", UNRESOLVED: "Unresolved" };

function machineSvg(run) {
  const visited = new Map();
  for (const t of run.events) visited.set(t.from + ">" + t.to, (visited.get(t.from + ">" + t.to) || 0) + 1);
  const reached = new Set(["INGEST", ...run.events.map((t) => t.to)]);
  const W = 860, H = 310, R = 34;
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, width: W, role: "img", "aria-label": "Agent state machine" });
  root.append(svg("defs", {},
    svg("marker", { id: "ah", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" }, svg("path", { d: "M0 0L10 5L0 10z", fill: "var(--muted)" })),
    svg("marker", { id: "ahv", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" }, svg("path", { d: "M0 0L10 5L0 10z", fill: "var(--accent)" }))));
  const seen = new Set();
  const edges = run.machine.filter((e) => e.event !== "ERROR");
  const pairs = new Set(edges.map((e) => e.from + ">" + e.to));
  for (const e of edges) {
    const key = e.from + ">" + e.to;
    if (seen.has(key)) continue;
    seen.add(key);
    const [x1, y1] = NODES[e.from], [x2, y2] = NODES[e.to];
    const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
    const ux = dx / len, uy = dy / len;
    const reciprocal = pairs.has(e.to + ">" + e.from);
    const bend = reciprocal ? 22 : 0;
    const sx = x1 + ux * R, sy = y1 + uy * R, ex = x2 - ux * (R + 4), ey = y2 - uy * (R + 4);
    const mx = (sx + ex) / 2 - uy * bend, my = (sy + ey) / 2 + ux * bend;
    const used = visited.has(key);
    const events = edges.filter((x) => x.from === e.from && x.to === e.to).map((x) => x.event.toLowerCase().replace(/_/g, " ")).join(" / ");
    const path = svg("path", { d: `M${sx} ${sy} Q${mx} ${my} ${ex} ${ey}`, fill: "none", stroke: used ? "var(--accent)" : "var(--line)", "stroke-width": used ? 2.4 : 1.4, "marker-end": used ? "url(#ahv)" : "url(#ah)" });
    path.append(svg("title", {}, events + (used ? " (taken " + visited.get(key) + "x)" : "")));
    root.append(path);
  }
  for (const [name, [x, y]] of Object.entries(NODES)) {
    const current = run.state === name;
    const done = reached.has(name) && !current;
    const terminalColor = name === "RESOLVED" ? "var(--ok)" : name === "UNRESOLVED" ? "var(--bad)" : "var(--accent)";
    const g = svg("g", {});
    g.append(svg("circle", { cx: x, cy: y, r: R, fill: current ? terminalColor : "var(--panel)", stroke: current || done ? terminalColor : "var(--line)", "stroke-width": current ? 3 : 2, class: current && !run.finished ? "pulse" : "" }));
    g.append(svg("text", { x, y: y + 4, "text-anchor": "middle", style: current ? "fill: var(--btn-ink); font-weight: 650" : "", "font-weight": 600 }, LABEL[name]));
    g.append(svg("title", {}, name + (current ? " (current)" : "")));
    root.append(g);
  }
  root.append(svg("text", { x: 14, y: H - 8, style: "fill: var(--muted); font-size: 11px" }, "An unexpected error from any working state also ends the run as Unresolved."));
  return root;
}

// -------------------------------------------------------------------------------------------- dependency graph
const ROLE_COLOR = { suspect: "var(--suspect)", upstream: "var(--upstream)", downstream: "var(--downstream)", test: "var(--test)", field: "var(--field)" };

function graphSvg(view) {
  if (!view || !view.nodes || !view.nodes.length) return el("p", { class: "hint" }, "No graph yet.");
  const col = (n) => n.role === "suspect" ? 0 : (n.role === "upstream" || n.role === "test") ? -Math.max(1, n.hops) : Math.max(1, n.hops);
  const cols = new Map();
  for (const n of view.nodes) {
    const c = col(n);
    if (!cols.has(c)) cols.set(c, []);
    cols.get(c).push(n);
  }
  const keys = [...cols.keys()].sort((a, b) => a - b);
  const maxRows = Math.max(...[...cols.values()].map((c) => c.length));
  const colW = 190, rowH = 38, padX = 90, padY = 24;
  const W = padX * 2 + (keys.length - 1) * colW, H = padY * 2 + Math.max(1, maxRows - 1) * rowH + 20;
  const pos = new Map();
  keys.forEach((k, i) => {
    const list = cols.get(k).sort((a, b) => a.label.localeCompare(b.label));
    const top = (H - (list.length - 1) * rowH) / 2;
    list.forEach((n, j) => pos.set(n.id, [padX + i * colW, top + j * rowH]));
  });
  const root = svg("svg", { viewBox: `0 0 ${Math.max(W, 300)} ${H}`, width: Math.max(W, 300), role: "img", "aria-label": "Call graph around the changed code" });
  root.append(svg("defs", {}, svg("marker", { id: "gh", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto" }, svg("path", { d: "M0 0L10 5L0 10z", fill: "var(--muted)" }))));
  for (const e of view.edges) {
    const a = pos.get(e.from), b = pos.get(e.to);
    if (!a || !b) continue;
    root.append(svg("line", { x1: a[0], y1: a[1], x2: b[0], y2: b[1], stroke: e.kind === "MODIFIES" ? "var(--warn)" : "var(--muted)", "stroke-width": 1.1, "stroke-dasharray": e.kind === "MODIFIES" ? "4 3" : "", "marker-end": "url(#gh)", opacity: .7 }));
  }
  for (const n of view.nodes) {
    const [x, y] = pos.get(n.id);
    const g = svg("g", {});
    g.append(svg("circle", { cx: x, cy: y, r: n.role === "suspect" ? 8 : 6, fill: ROLE_COLOR[n.role] || "var(--muted)" }));
    g.append(svg("text", { x, y: y - 11, "text-anchor": "middle", style: "font-size: 10.5px" }, n.label.length > 26 ? n.label.slice(0, 25) + "…" : n.label));
    g.append(svg("title", {}, n.label + " - " + n.role + " - " + n.file));
    root.append(g);
  }
  return root;
}

function legend() {
  return el("div", { class: "legend" },
    ...[["suspect", "changed / suspect"], ["upstream", "callers (blast radius)"], ["test", "tests that exercise it"], ["downstream", "callees"], ["field", "fields written"]]
      .map(([k, t]) => el("span", null, el("i", { style: "background:" + ROLE_COLOR[k] }), t)));
}

// ------------------------------------------------------------------------------------------------ diff viewer
function parseDiff(text) {
  const files = [];
  let cur = null, hunk = null, oldNo = 0, newNo = 0, dels = [], adds = [];
  const flush = () => {
    const n = Math.max(dels.length, adds.length);
    for (let i = 0; i < n; i++) hunk.rows.push({ l: dels[i] || null, r: adds[i] || null });
    dels = []; adds = [];
  };
  for (const line of text.split("\n")) {
    if (line.startsWith("--- ")) { cur = { path: line.slice(4).replace(/^a\//, ""), hunks: [] }; files.push(cur); hunk = null; }
    else if (line.startsWith("+++ ") || line.startsWith("diff ") || line.startsWith("index ")) continue;
    else if (line.startsWith("@@") && cur) {
      if (hunk) flush();
      const m = /@@ -(\d+)(?:,\d+)? \+(\d+)/.exec(line);
      oldNo = m ? +m[1] : 1; newNo = m ? +m[2] : 1;
      hunk = { header: line, rows: [] }; cur.hunks.push(hunk);
    } else if (hunk && line.startsWith("-")) dels.push({ n: oldNo++, t: line.slice(1) });
    else if (hunk && line.startsWith("+")) adds.push({ n: newNo++, t: line.slice(1) });
    else if (hunk && (line.startsWith(" ") || line === "")) {
      if (line === "" ) continue;
      flush();
      hunk.rows.push({ l: { n: oldNo++, t: line.slice(1) }, r: { n: newNo++, t: line.slice(1) }, same: true });
    }
  }
  if (hunk) flush();
  return files;
}

function diffView(text) {
  if (!text) return el("p", { class: "hint" }, "No verified patch yet.");
  const wrap = el("div");
  for (const f of parseDiff(text)) {
    const box = el("div", { class: "diff" }, el("div", { class: "file" }, f.path));
    const grid = el("div", { class: "grid" });
    for (const h of f.hunks) {
      grid.append(el("div", { class: "hunk" }, h.header));
      for (const row of h.rows) {
        const side = (x, kind) => el("div", { class: "cell " + (x && !row.same ? kind : "") }, el("span", { class: "n" }, x ? x.n : ""), el("span", { class: "c" }, x ? x.t : ""));
        grid.append(side(row.l, "del"), side(row.r, "add"));
      }
    }
    box.append(grid);
    wrap.append(box);
  }
  return wrap;
}

// ------------------------------------------------------------------------------------------------- run page
function suspectsTable(suspects) {
  if (!suspects.length) return el("p", { class: "hint" }, "No suspects yet.");
  const bar = (name, v) => el("div", null, el("small", null, name), el("div", { class: "bar", title: v.toFixed(2) }, el("i", { style: "width:" + Math.round(100 * v) + "%" })));
  return el("div", { class: "scroll" }, el("table", null,
    el("thead", null, el("tr", null, ...["#", "Method", "Score", "Evidence"].map((h) => el("th", null, h)))),
    el("tbody", null, ...suspects.slice(0, 6).map((s) => el("tr", null,
      el("td", null, s.rank),
      el("td", null, el("div", { class: "mono" }, s.label), el("small", { class: "hint" }, s.file + ":" + s.startLine + "-" + s.endLine)),
      el("td", { class: "mono" }, s.score.toFixed(2)),
      el("td", null, el("div", { class: "sig" }, bar("spectrum", s.signals.spectrum), bar("stack", s.signals.frame), bar("distance", s.signals.proximity), bar("text", s.signals.text))))))));
}

function attemptsTable(attempts) {
  if (!attempts.length) return el("p", { class: "hint" }, "No candidates verified yet.");
  return el("div", { class: "scroll" }, el("table", null,
    el("thead", null, el("tr", null, ...["Round", "Candidate", "Compiles", "Fixed", "Broke", "Time", "Result"].map((h) => el("th", null, h)))),
    el("tbody", null, ...attempts.map((a) => el("tr", { title: a.critique },
      el("td", null, a.round + "." + a.rank),
      el("td", null, el("span", { class: "mono" }, a.operator), " ", a.description),
      el("td", null, a.applied ? (a.compiled ? "yes" : "no") : "did not apply"),
      el("td", null, a.fixed), el("td", null, a.broken),
      el("td", { class: "mono" }, fmtMs(a.ms)),
      el("td", null, el("b", { style: "color:" + (a.passes ? "var(--ok)" : "var(--muted)") }, a.passes ? "passes all tests" : a.timedOut ? "timed out" : a.stillFailing + " still failing")))))));
}

function renderRun(run) {
  const tel = run.telemetry;
  const main = $("main");
  const status = run.finished ? run.state.toLowerCase() : run.state.toLowerCase() + "...";
  const parts = [];
  parts.push(el("div", { class: "card" },
    el("div", { class: "title" }, el("h1", null, run.label), el("span", { class: "badge " + (run.finished ? run.state : "live") }, status), el("span", { class: "hint" }, run.outcome || "")),
    el("div", { class: "kv" },
      el("div", null, el("span", null, "Latency"), el("b", null, fmtMs(run.latencyMs))),
      el("div", null, el("span", null, "Candidates verified"), el("b", null, tel.candidatesVerified)),
      el("div", null, el("span", null, "Sandbox runs"), el("b", null, tel.sandboxRuns)),
      el("div", null, el("span", null, "Model tokens in / out"), el("b", null, tel.inputTokens.toLocaleString() + " / " + tel.outputTokens.toLocaleString())),
      el("div", null, el("span", null, "Model cost"), el("b", null, usd(tel.costUsd))),
      el("div", null, el("span", null, "Tests before"), el("b", null, run.failingTests.length + " failing / " + run.baselinePassing + " passing")))));

  parts.push(el("div", { class: "card" }, el("h2", null, "Agent state machine"), machineSvg(run),
    el("ol", { class: "log" }, ...run.events.map((t) => el("li", null, el("span", { class: "ms" }, t.from.toLowerCase() + " → " + t.to.toLowerCase() + " · " + fmtMs(t.msInFrom)), el("span", null, t.reason))))));

  if (run.failingTests.length) parts.push(el("div", { class: "card" }, el("h2", null, "What failed"), el("ul", null, ...run.failingTests.slice(0, 6).map((f) => el("li", { class: "mono" }, f)))));
  parts.push(el("div", { class: "card" }, el("h2", null, "Where the bug probably is"), suspectsTable(run.suspects),
    run.graph && run.graph.nodes ? el("p", { class: "hint" }, `Code graph: ${run.graph.nodes} nodes, ${run.graph.calls} calls, ${run.graph.imports} imports, ${run.graph.inherits} inheritance, ${run.graph.modifies} field writes.`) : null));
  parts.push(el("div", { class: "card" }, el("h2", null, "Blast radius"), graphSvg(run.graphView), legend(),
    run.blast && run.blast.upstream ? el("p", { class: "hint" }, `${run.blast.upstream.length} method(s) call the changed code; ${run.blast.tests.length} test(s) exercise it.`) : null));
  parts.push(el("div", { class: "card" }, el("h2", null, "Candidates tried"), attemptsTable(run.attempts)));

  const actions = el("div", { class: "actions" });
  if (run.diff) {
    actions.append(el("a", { class: "btn secondary", href: "/api/runs/" + run.id + "/patch", download: "" }, "Download .patch"));
    if (run.resolved) actions.append(el("button", { type: "button", disabled: run.approved || approving, onclick: () => approve(run.id) }, run.approved ? "Pull request opened" : "Approve and open pull request"));
    if (run.prUrl) actions.append(el("span", { class: "mono" }, run.prUrl));
    actions.append(el("span", { id: "approveMsg", class: "hint", role: "status" }));
  }
  parts.push(el("div", { class: "card" }, el("h2", null, "Proposed change"), run.rationale ? el("p", null, run.rationale) : null, diffView(run.diff), actions));
  if (run.pr) parts.push(el("div", { class: "card" }, el("h2", null, "Pull request description"), el("pre", { class: "pr" }, run.pr)));
  parts.push(el("div", { class: "card" }, el("h2", null, "Isolation"), el("p", { class: "mono" }, run.sandbox), el("p", { class: "hint" }, "Synthesizer: " + run.synthesizer),
    el("div", { class: "scroll" }, el("table", null, el("thead", null, el("tr", null, el("th", null, "Phase"), el("th", null, "Time"), el("th", null, ""))),
      el("tbody", null, ...Object.entries(tel.phaseMs).map(([k, v]) => el("tr", null, el("td", null, k.toLowerCase()), el("td", { class: "mono" }, fmtMs(v)),
        el("td", null, el("div", { class: "bar" }, el("i", { style: "width:" + Math.min(100, Math.round(100 * v / Math.max(1, run.latencyMs))) + "%" }))))))))));
  main.replaceChildren(...parts);
}

async function approve(id) {
  approving = true;
  try {
    const r = await api("/api/runs/" + id + "/approve", { method: "POST" });
    lastRun = null;
    await refreshRun();
    const m = $("approveMsg"); if (m) m.textContent = r.note + (r.url ? " " + r.url : "");
  } catch (e) {
    const m = $("approveMsg"); if (m) { m.textContent = e.message; m.className = "hint err"; }
  } finally { approving = false; }
}

$("start").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const path = $("repoPath").value.trim();
  if (!path) return;
  $("startMsg").textContent = "Queuing...";
  try {
    const r = await api("/api/runs", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ repoPath: path }) });
    $("startMsg").textContent = "Queued run " + r.runId;
    selected = r.runId;
    refreshList(); refreshRun();
  } catch (e) { $("startMsg").textContent = e.message; $("startMsg").className = "hint err"; }
});

setInterval(refreshList, 2500);
setInterval(refreshMetrics, 2500);
setInterval(refreshRun, 1000);
refreshList(); refreshMetrics();

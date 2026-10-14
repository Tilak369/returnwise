// Shared helpers for the customer portal and the operations dashboard.

const RW = {
  esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  },

  async api(path, opts = {}) {
    const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || `Request failed (${res.status})`);
    return body;
  },

  resolutionLabel: {
    REFUND: "Refund approved",
    EXCHANGE: "Exchange approved",
    SIZE_SWAP: "Size swap approved",
    STORE_CREDIT: "Store credit",
    REJECT: "Not eligible",
    MANUAL_REVIEW: "Sent for review",
  },

  badge(resolution) {
    const cls = { REFUND: "b-ok", EXCHANGE: "b-ok", SIZE_SWAP: "b-ok", STORE_CREDIT: "b-info", REJECT: "b-bad", MANUAL_REVIEW: "b-warn" }[resolution] || "b-info";
    return `<span class="badge ${cls}">${RW.esc(RW.resolutionLabel[resolution] || resolution)}</span>`;
  },

  riskColor(score) {
    return score >= 70 ? "var(--bad)" : score >= 40 ? "var(--warn)" : "var(--ok)";
  },

  riskMeter(score) {
    return `<div><b>${score}</b>/100</div><div class="meter"><i style="width:${Math.min(100, score)}%;background:${RW.riskColor(score)}"></i></div>`;
  },

  traceHtml(trace) {
    const label = { TOOL_CALL: "Called", TOOL_RESULT: "Result", DECISION: "Decision", GUARDRAIL: "Guardrail", NOTE: "Note" };
    return `<ul class="trace">${(trace || []).map(s => `
      <li class="${RW.esc(s.type)}"><span class="dot"></span>
        <div><b>${RW.esc(label[s.type] || s.type)}: ${RW.esc(s.name)}</b><br><code>${RW.esc(s.detail)}</code></div>
      </li>`).join("")}</ul>`;
  },

  decisionDetails(c) {
    const d = c.decision;
    const exch = d.exchangeSku ? `<dt>Replacement</dt><dd>${RW.esc(d.exchangeSku)} · size ${RW.esc(d.exchangeSize)}</dd>` : "";
    return `
      <dl class="kv">
        <dt>Reason</dt><dd>${RW.esc(d.reasonCategory)}</dd>
        ${exch}
        <dt>Fraud risk</dt><dd>${RW.riskMeter(d.fraudRiskScore)}</dd>
        <dt>Signals</dt><dd>${(d.riskSignals || []).map(RW.esc).join("<br>") || "—"}</dd>
        <dt>Photo</dt><dd>${RW.esc(d.photoAssessment || "—")}</dd>
        <dt>Rationale</dt><dd>${RW.esc(d.internalRationale)}</dd>
        <dt>Product insight</dt><dd>${RW.esc(d.productInsight || "—")}</dd>
        <dt>Engine</dt><dd>${RW.esc(c.engine)}</dd>
      </dl>`;
  },

  async showEngine() {
    try {
      const info = await RW.api("/api/info");
      const el = document.getElementById("engine");
      if (el) el.textContent = `${info.engine} · ${info.store}`;
      return info;
    } catch { return null; }
  },
};

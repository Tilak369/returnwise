// Return anomaly detection. Pure code, no AI: it decides WHAT is unusual, with statistics,
// so the finding is reproducible. Gemini is then asked WHY and WHAT TO DO (see app.js).
//
// Data: an 8-week sales and returns history for the demo store (simulated, seeded so it is
// the same on every load), plus this week's real return cases from Firestore.

import { products } from "./seed-data.js";

export const WEEKS = 8;
export const REASONS = ["SIZE_FIT", "DEFECT", "CHANGED_MIND", "NOT_AS_DESCRIBED"];
const CHANNELS = ["ONLINE", "STORE"];
const MARKETS = ["SG", "MY"];

// Small deterministic PRNG (mulberry32) so the history never changes between loads.
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Binomial draw via normal approximation (fine for these volumes), never negative.
function draw(rand, n, p) {
  if (n <= 0 || p <= 0) return 0;
  const mean = n * p, sd = Math.sqrt(n * p * (1 - p));
  const u = Math.max(rand(), 1e-9), v = rand();
  const z = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  return Math.max(0, Math.round(mean + z * sd));
}

// Normal return rates by reason, per category (fashion e-commerce ballpark).
const BASE = {
  JEANS: { SIZE_FIT: 0.08, DEFECT: 0.01, CHANGED_MIND: 0.04, NOT_AS_DESCRIBED: 0.015 },
  OUTERWEAR: { SIZE_FIT: 0.05, DEFECT: 0.012, CHANGED_MIND: 0.04, NOT_AS_DESCRIBED: 0.015 },
  DRESSES: { SIZE_FIT: 0.06, DEFECT: 0.01, CHANGED_MIND: 0.07, NOT_AS_DESCRIBED: 0.02 },
  FOOTWEAR: { SIZE_FIT: 0.07, DEFECT: 0.01, CHANGED_MIND: 0.03, NOT_AS_DESCRIBED: 0.01 },
  SHIRTS: { SIZE_FIT: 0.05, DEFECT: 0.008, CHANGED_MIND: 0.04, NOT_AS_DESCRIBED: 0.02 },
};

// Patterns planted in the simulated history; the detector is never told about them.
function multiplier(p, size, channel, market, week, reason) {
  let m = 1;
  if (p.sku === "LL-CT-WOOL-CAM" && size === "M" && week >= 5 && reason === "DEFECT") m *= 9; // a bad batch
  if (p.sku === "LL-JN-SLIM-IND" && channel === "ONLINE" && reason === "SIZE_FIT") m *= 2.6; // runs small
  if (p.sku === "LL-DR-WRAP-EMR" && market === "SG" && week === WEEKS && reason === "CHANGED_MIND") m *= 4; // event season
  if (p.sku === "LL-SH-LIN-SKY" && channel === "ONLINE" && reason === "NOT_AS_DESCRIBED") m *= 4; // colour on screen
  return m;
}

export function simulatedHistory(seed = 20261016) {
  const rand = rng(seed);
  const rows = [];
  for (const p of products) {
    const sizes = p.sizes || Object.keys(p.stockBySize);
    for (let week = 1; week <= WEEKS; week++) {
      for (const size of sizes) {
        for (const channel of CHANNELS) {
          for (const market of MARKETS) {
            const sold = Math.round((channel === "ONLINE" ? 34 : 22) * (market === "SG" ? 1 : 0.7)
              * (0.85 + rand() * 0.3) * (p.price > 150 ? 0.6 : 1));
            const returns = {};
            for (const r of REASONS) {
              const rate = Math.min(0.9, BASE[p.category][r] * multiplier(p, size, channel, market, week, r));
              returns[r] = draw(rand, sold, rate);
            }
            rows.push({ week, sku: p.sku, size, channel, market, sold, returns });
          }
        }
      }
    }
  }
  return rows;
}

// Fold this week's real return cases into the latest week (one per order per day,
// so repeated test submissions of the same order don't count as many returns).
export function withLiveCases(rows, cases, now = Date.now()) {
  const out = rows.map((r) => ({ ...r, returns: { ...r.returns } }));
  const seen = new Set();
  let added = 0;
  for (const c of cases || []) {
    const t = Date.parse(c.createdAt);
    if (!(now - t < 7 * 864e5)) continue;
    const reason = c.decision?.reasonCategory;
    if (!REASONS.includes(reason)) continue;
    const key = c.orderId + "|" + String(c.createdAt).slice(0, 10);
    if (seen.has(key)) continue;
    seen.add(key);
    const row = out.find((r) => r.week === WEEKS && r.sku === c.sku && r.size === c.size
      && r.channel === "ONLINE" && r.market === "SG");
    if (row) { row.returns[reason] += 1; added++; }
  }
  return { rows: out, liveCasesAdded: added };
}

const sum = (rows, f) => rows.reduce((s, r) => s + f(r), 0);

function zTwoProportions(r1, n1, r2, n2) {
  const p = (r1 + r2) / (n1 + n2);
  const se = Math.sqrt(p * (1 - p) * (1 / n1 + 1 / n2));
  return se ? (r1 / n1 - r2 / n2) / se : 0;
}

const DIM_LABEL = { size: "size", channel: "channel", market: "market" };
const VALUE_LABEL = { ONLINE: "online", STORE: "in store", SG: "Singapore", MY: "Malaysia" };
const REASON_LABEL = {
  SIZE_FIT: "size and fit", DEFECT: "faults", CHANGED_MIND: "changed mind", NOT_AS_DESCRIBED: "not as described",
};

// Rule-based next step, always available even when Gemini is busy.
export function ruleAction(a) {
  switch (a.reason) {
    case "DEFECT":
      return { owner: "Quality", action: `Hold remaining ${a.name} stock${a.dimension === "size" ? ` in size ${a.value}` : ""} and ask the supplier to inspect the latest batch.` };
    case "SIZE_FIT":
      return { owner: "E-commerce", action: `Update the size guide and fit note for ${a.name}${a.value === "ONLINE" ? " on the website" : ""}, and recommend sizing up or down.` };
    case "CHANGED_MIND":
      return { owner: "Loss prevention", action: `Check recent ${a.name} returns${a.dimension === "market" ? ` in ${VALUE_LABEL[a.value] || a.value}` : ""} for worn or wardrobed items; consider a tags-on rule for occasion wear.` };
    case "NOT_AS_DESCRIBED":
      return { owner: "Content", action: `Reshoot or colour-correct the ${a.name} product photos and tighten the description.` };
    default:
      return { owner: "Merchandising", action: `Review ${a.name}.` };
  }
}

/**
 * Two tests, each per product and return reason:
 *  - segment: is one size / channel / market returned far more often than the rest of the product?
 *    (two-proportion z-test over all weeks)
 *  - spike: is this week far above the product's own weekly average for that market?
 *    (Poisson z-score against weeks 1..N-1)
 */
export function detectAnomalies(rows, { minZ = 3, minReturns = 8, minRatio = 1.8, limit = 5 } = {}) {
  const found = [];
  for (const p of products) {
    const pr = rows.filter((r) => r.sku === p.sku);
    if (!pr.length) continue;
    for (const reason of REASONS) {
      for (const dim of ["size", "channel", "market"]) {
        const values = [...new Set(pr.map((r) => r[dim]))];
        for (const v of values) {
          const seg = pr.filter((r) => r[dim] === v), rest = pr.filter((r) => r[dim] !== v);
          const r1 = sum(seg, (r) => r.returns[reason]), n1 = sum(seg, (r) => r.sold);
          const r2 = sum(rest, (r) => r.returns[reason]), n2 = sum(rest, (r) => r.sold);
          if (!n1 || !n2 || r1 < minReturns) continue;
          const rate = r1 / n1, baseline = r2 / n2, ratio = baseline ? rate / baseline : Infinity;
          const z = zTwoProportions(r1, n1, r2, n2);
          if (z >= minZ && ratio >= minRatio) {
            found.push({ kind: "segment", sku: p.sku, name: p.name, reason, dimension: dim, value: v,
              returns: r1, sold: n1, rate, baseline, ratio, z });
          }
        }
      }
      for (const market of MARKETS) {
        const weekly = [];
        for (let w = 1; w <= WEEKS; w++) {
          weekly.push(sum(pr.filter((r) => r.week === w && r.market === market), (r) => r.returns[reason]));
        }
        const x = weekly[WEEKS - 1];
        const prior = weekly.slice(0, -1), mu = prior.reduce((a, b) => a + b, 0) / prior.length;
        const z = mu > 0 ? (x - mu) / Math.sqrt(mu) : 0;
        if (x >= minReturns && z >= minZ + 1 && x >= 2 * mu) {
          found.push({ kind: "spike", sku: p.sku, name: p.name, reason, dimension: "market", value: market,
            returns: x, weekly, rate: x, baseline: mu, ratio: mu ? x / mu : Infinity, z });
        }
      }
    }
  }
  // Keep the strongest finding per product and reason, then the top few overall.
  const best = new Map();
  for (const a of found) {
    const k = a.sku + "|" + a.reason;
    if (!best.has(k) || best.get(k).z < a.z) best.set(k, a);
  }
  return [...best.values()].sort((a, b) => b.z - a.z).slice(0, limit).map((a, i) => ({
    id: "A" + (i + 1), ...a, ...describe(a), suggested: ruleAction(a),
    severity: a.z >= 8 ? "high" : a.z >= 5 ? "medium" : "low",
  }));
}

function describe(a) {
  const pct = (x) => (x * 100).toFixed(1) + "%";
  const what = REASON_LABEL[a.reason];
  if (a.kind === "spike") {
    return {
      title: `${a.name}: "${what}" returns spiked this week in ${VALUE_LABEL[a.value]}`,
      detail: `${a.returns} this week vs about ${a.baseline.toFixed(1)} a week before (${a.ratio.toFixed(1)}×).`,
    };
  }
  const seg = a.dimension === "size" ? `size ${a.value}` : VALUE_LABEL[a.value] || a.value;
  return {
    title: `${a.name}, ${seg}: returned for ${what} ${a.ratio.toFixed(1)}× more than the rest`,
    detail: `${pct(a.rate)} of ${a.sold} sold vs ${pct(a.baseline)} for other ${DIM_LABEL[a.dimension]}s (${a.returns} returns).`,
  };
}

// ---------------------------------------------------------------- Gemini investigation
export const INVESTIGATION_PROMPT = `You are a retail returns analyst for the fashion brand Lumen & Loom.
Statistical code has already flagged unusual return patterns. For each one, explain the most likely
cause using the product facts and what customers actually wrote, and recommend ONE concrete next step
that a named team can act on this week. Be specific and brief (each field under 35 words).
Do not invent numbers beyond those given, and do not contradict the product facts (for example,
the fit note already on the product page). If the evidence is thin, say so in "likelyCause".`;

export function investigationInput(anoms, cases = []) {
  return anoms.map((a) => {
    const p = products.find((x) => x.sku === a.sku) || {};
    const said = cases.filter((c) => c.sku === a.sku && c.decision?.reasonCategory === a.reason)
      .map((c) => c.customerMessage).filter(Boolean);
    const insightsSeen = [...new Set(cases.filter((c) => c.sku === a.sku)
      .map((c) => c.decision?.productInsight).filter(Boolean))];
    return {
      id: a.id, finding: a.title, numbers: a.detail, reason: a.reason,
      weeklyCountsOldestFirst: a.weekly,
      product: { name: p.name, category: p.category, description: p.description, fitNote: p.fitNote, price: p.price },
      customerMessages: [...new Set(said)].slice(0, 4),
      agentProductInsights: insightsSeen.slice(0, 3),
    };
  });
}

// Run: node --test web/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { runAgent, toolDeclarations, insights } from "../agent.js";
import { products, customers, orders } from "../seed-data.js";

function memoryStore() {
  const P = new Map(products.map((p) => [p.sku, structuredClone(p)]));
  const C = new Map(customers.map((c) => [c.customerId, c]));
  const O = new Map(orders.map((o) => [o.orderId, o]));
  const cases = [];
  return {
    P, cases,
    getOrder: async (id) => O.get(id) || null,
    getCustomer: async (id) => C.get(id) || null,
    getProduct: async (sku) => P.get(sku) || null,
    productsByCategory: async (cat) => [...P.values()].filter((p) => p.category === cat),
    casesByCustomer: async (id) => cases.filter((c) => c.customerId === id),
    saveCase: async (rc) => cases.push(rc),
    reserveStock: async (sku, size) => {
      const p = P.get(sku);
      if (!p || !(p.stockBySize[size] > 0)) return false;
      p.stockBySize[size] -= 1;
      return true;
    },
  };
}

// Scripted chat: returns the given function calls in order, records what was sent.
function scripted(...turns) {
  const sent = [];
  return {
    sent,
    startChat: () => ({
      sendMessage: async (parts) => {
        sent.push(parts);
        const calls = turns.shift() || [];
        return { response: { functionCalls: () => calls } };
      },
    }),
  };
}

const decision = (args) => [{ name: "submit_decision", args }];

test("size swap: tools run, results go back to Gemini, stock is reserved", async () => {
  const store = memoryStore();
  const g = scripted(
    [{ name: "get_order", args: { order_id: "LL-10021" } }],
    [{ name: "check_inventory", args: { sku: "LL-JN-SLIM-IND", size: "33" } }],
    decision({
      resolution: "SIZE_SWAP", exchange_sku: "LL-JN-SLIM-IND", exchange_size: "33", fraud_risk_score: 5,
      reason_category: "SIZE_FIT", internal_rationale: "Runs small.", customer_reply: "Hi Priya, size 33 is on its way.",
    }),
  );
  const rc = await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10021", sku: "LL-JN-SLIM-IND", message: "Too tight" } });

  assert.equal(rc.status, "AUTO_APPROVED");
  assert.equal(rc.decision.resolution, "SIZE_SWAP");
  assert.equal(store.P.get("LL-JN-SLIM-IND").stockBySize["33"], 39);
  assert.match(JSON.stringify(g.sent[1]), /functionResponse.*withinReturnWindow/);
  assert.deepEqual([...new Set(rc.trace.map((t) => t.type))], ["TOOL_CALL", "TOOL_RESULT", "DECISION", "GUARDRAIL"]);
  assert.equal(store.cases.length, 1);
});

test("high-risk refund is forced to manual review", async () => {
  const store = memoryStore();
  const g = scripted(decision({ resolution: "REFUND", fraud_risk_score: 85, reason_category: "CHANGED_MIND",
    internal_rationale: "Serial returner.", customer_reply: "Refund approved." }));
  const rc = await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10023", sku: "LL-DR-WRAP-EMR", message: "Refund" } });
  assert.equal(rc.decision.resolution, "MANUAL_REVIEW");
  assert.equal(rc.status, "NEEDS_REVIEW");
  assert.doesNotMatch(rc.decision.customerReply, /Refund approved/);
});

test("exchange into an out-of-stock size is blocked", async () => {
  const store = memoryStore();
  const g = scripted(decision({ resolution: "SIZE_SWAP", exchange_sku: "LL-JN-SLIM-IND", exchange_size: "32",
    fraud_risk_score: 5, reason_category: "SIZE_FIT", internal_rationale: "x", customer_reply: "y" }));
  const rc = await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10021", sku: "LL-JN-SLIM-IND", message: "Too tight" } });
  assert.equal(rc.decision.resolution, "MANUAL_REVIEW");
});

test("photo is sent to Gemini as inlineData", async () => {
  const store = memoryStore();
  const g = scripted(decision({ resolution: "REFUND", fraud_risk_score: 10, reason_category: "DEFECT",
    internal_rationale: "Seam split.", customer_reply: "Refund approved." }));
  await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10022", sku: "LL-CT-WOOL-CAM", message: "Seam split", photoBase64: "aGVsbG8=", photoMimeType: "image/jpeg" } });
  assert.equal(g.sent[0][1].inlineData.data, "aGVsbG8=");
});

test("tool declarations build with a Schema-like builder", () => {
  const S = new Proxy({}, { get: (_, k) => (o = {}) => ({ type: k, ...o }) });
  const decls = toolDeclarations(S);
  assert.equal(decls.length, 6);
  assert.ok(decls.find((d) => d.name === "submit_decision").parameters.properties.resolution.enum.includes("SIZE_SWAP"));
});

test("insights aggregate revenue kept and reasons", () => {
  const ins = insights([
    { sku: "A", price: 50, status: "AUTO_APPROVED", decision: { resolution: "SIZE_SWAP", reasonCategory: "SIZE_FIT", fraudRiskScore: 5, productInsight: "runs small" } },
    { sku: "B", price: 20, status: "NEEDS_REVIEW", decision: { resolution: "MANUAL_REVIEW", reasonCategory: "CHANGED_MIND", fraudRiskScore: 80 } },
  ], {});
  assert.equal(ins.revenueRetainedValue, 50);
  assert.equal(ins.needsReview, 1);
  assert.equal(ins.averageRiskScore, 43);
});

test("facts are pre-fetched into the first message", async () => {
  const store = memoryStore();
  const g = scripted(decision({ resolution: "REFUND", fraud_risk_score: 5, reason_category: "CHANGED_MIND",
    internal_rationale: "x", customer_reply: "y" }));
  const rc = await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10025", sku: "LL-SH-LIN-SKY", message: "Colour differs" } });
  assert.match(g.sent[0][0].text, /get_customer_history -> .*Priya Raman/);
  assert.equal(g.sent.length, 1, "one Gemini call when the model decides straight away");
  assert.equal(rc.trace.filter((t) => t.detail.includes("pre-fetched")).length, 3);
});

test("429 from Gemini is retried after the requested delay", async () => {
  const store = memoryStore();
  let calls = 0;
  const statuses = [];
  const startChat = () => ({
    sendMessage: async () => {
      calls++;
      if (calls === 1) throw new Error("[429 ] You exceeded your current quota. Please retry in 0.01s.");
      return { response: { functionCalls: () => decision({ resolution: "REFUND", fraud_risk_score: 5,
        reason_category: "DEFECT", internal_rationale: "x", customer_reply: "y" }) } };
    },
  });
  const rc = await runAgent({ startChat, store, engine: "test", onStatus: (s) => statuses.push(s),
    request: { orderId: "LL-10022", sku: "LL-CT-WOOL-CAM", message: "Seam split" } });
  assert.equal(rc.decision.resolution, "REFUND");
  assert.equal(calls, 2);
  assert.ok(statuses.some((s) => /retrying in \d+s/.test(s)));
});

test("late, non-faulty return cannot be auto-approved", async () => {
  const store = memoryStore();
  const g = scripted(decision({ resolution: "SIZE_SWAP", exchange_sku: "LL-SN-RUN-WHT", exchange_size: "41",
    fraud_risk_score: 10, reason_category: "SIZE_FIT", internal_rationale: "x", customer_reply: "Swap approved" }));
  const rc = await runAgent({ startChat: g.startChat, store, engine: "test",
    request: { orderId: "LL-10024", sku: "LL-SN-RUN-WHT", message: "Too big" } });
  assert.equal(rc.decision.resolution, "MANUAL_REVIEW");
  assert.equal(store.P.get("LL-SN-RUN-WHT").stockBySize["41"], 20, "no stock reserved");
});

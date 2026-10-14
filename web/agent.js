// ReturnWise agent: Gemini function-calling loop + business tools + guardrails.
// Pure module: the Gemini chat and the data store are injected, so the same code
// runs in the browser (Firebase AI Logic + Firestore) and in Node tests (stubs).

export const RETURN_WINDOW_DAYS = 30;
export const MANUAL_REVIEW_THRESHOLD = 70;
const MAX_TURNS = 8;
const AUTO_RESOLUTIONS = ["REFUND", "EXCHANGE", "SIZE_SWAP", "STORE_CREDIT"];

export const POLICY = `Lumen & Loom returns policy:
1. Online orders can be returned or exchanged within ${RETURN_WINDOW_DAYS} days of delivery.
2. Items must be unworn, unwashed and have tags attached, unless the item is faulty.
3. Faulty or damaged items: full refund or like-for-like exchange, any time within 90 days.
4. Size exchanges are free and preferred when the requested size is in stock.
5. Returns after the window may be offered store credit at the business's discretion for loyal (PLUS) members.
6. Cases with high fraud risk (worn items, serial returns of occasion wear, photo not matching the item)
   must go to MANUAL_REVIEW rather than being rejected automatically.`;

export const SYSTEM_PROMPT = `You are ReturnWise, the returns and exchange agent for Lumen & Loom, a fashion retailer.
Your goals, in order:
1. Keep the customer: prefer a size swap or exchange over a refund whenever it genuinely solves the problem.
2. Be fair and fast for genuine customers; never make them prove their honesty.
3. Protect the business from return abuse (wardrobing, serial returns, photos that do not match the item).
4. Turn every return into a product insight the merchandising team can act on.

How to work:
- Always call get_order first, then get_customer_history and check_inventory for the item.
- Call get_return_policy if the case touches policy (outside window, worn, faulty).
- For SIZE_FIT reasons, use the product fitNote and stock to pick the right replacement size.
  If the right size is out of stock, call find_alternatives for the same category.
- If a photo is provided, inspect it: does it show the ordered product (category, colour)?
  Visible defect? Signs of wear (creases, stains, missing tags, deodorant marks)? Describe what you see.
- Fraud risk score 0-100. High returns in 90 days, high-value occasion wear near the end of the window,
  wear in the photo, or a photo that does not match the item all raise it. Loyal low-return customers lower it.
- Never auto-REJECT a high-risk case; use MANUAL_REVIEW so a human decides.
- Only propose an exchange size or SKU that check_inventory or find_alternatives shows in stock.
- customer_reply: warm, short (max 80 words), plain English, addressed to the customer by first name,
  tells them exactly what happens next. Never mention fraud scores or internal notes to the customer.
- Finish by calling submit_decision exactly once.`;

// ---------------------------------------------------------------- tool declarations
// `S` is the Firebase AI `Schema` builder (injected so tests can pass a plain stub).
export function toolDeclarations(S) {
  return [
    {
      name: "get_order",
      description: "Fetch an order with delivery date, items and whether it is still within the return window.",
      parameters: S.object({ properties: { order_id: S.string({ description: "Order id, e.g. LL-10021" }) } }),
    },
    {
      name: "get_customer_history",
      description: "Fetch the customer's loyalty tier, order and return counts, and earlier return cases.",
      parameters: S.object({ properties: { customer_id: S.string({ description: "Customer id, e.g. C-1001" }) } }),
    },
    {
      name: "check_inventory",
      description: "Check live stock for a SKU, including every size, and the product fit note.",
      parameters: S.object({
        properties: { sku: S.string({ description: "Product SKU" }), size: S.string({ description: "Size to check" }) },
      }),
    },
    {
      name: "find_alternatives",
      description: "List other products in the same category with their stock by size.",
      parameters: S.object({
        properties: {
          category: S.string({ description: "Category, e.g. JEANS" }),
          exclude_sku: S.string({ description: "SKU to leave out" }),
        },
        optionalProperties: ["exclude_sku"],
      }),
    },
    {
      name: "get_return_policy",
      description: "Return the store's returns and exchange policy text.",
    },
    {
      name: "submit_decision",
      description: "Submit the final decision for this return. Call exactly once, last.",
      parameters: S.object({
        properties: {
          resolution: S.enumString({
            description: "Final outcome",
            enum: ["REFUND", "EXCHANGE", "SIZE_SWAP", "STORE_CREDIT", "REJECT", "MANUAL_REVIEW"],
          }),
          exchange_sku: S.string({ description: "SKU to send as replacement (EXCHANGE or SIZE_SWAP only)" }),
          exchange_size: S.string({ description: "Size to send as replacement (EXCHANGE or SIZE_SWAP only)" }),
          fraud_risk_score: S.integer({ description: "0 = no risk, 100 = almost certainly abusive" }),
          risk_signals: S.array({ description: "Short risk or trust signals observed", items: S.string() }),
          photo_assessment: S.string({ description: "What the photo shows (match, defect, wear), or 'No photo provided'" }),
          reason_category: S.enumString({
            description: "Root cause of the return",
            enum: ["SIZE_FIT", "DEFECT", "NOT_AS_DESCRIBED", "CHANGED_MIND", "WRONG_ITEM", "OTHER"],
          }),
          internal_rationale: S.string({ description: "2-4 sentences for the ops team explaining the decision" }),
          customer_reply: S.string({ description: "Message to the customer (max 80 words)" }),
          product_insight: S.string({ description: "One actionable insight for merchandising or the product page" }),
        },
        optionalProperties: ["exchange_sku", "exchange_size", "risk_signals", "photo_assessment", "product_insight"],
      }),
    },
  ];
}

// ---------------------------------------------------------------- helpers
export function deliveredDate(order, today = new Date()) {
  const d = new Date(today);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - (order.deliveredDaysAgo ?? 0));
  return d.toISOString().slice(0, 10);
}

export const firstName = (full) => (full ? String(full).split(" ")[0] : "there");

const abbreviate = (s) => (s.length <= 700 ? s : s.slice(0, 700) + "…");

// ---------------------------------------------------------------- tools
export function makeTools(store) {
  const tools = {
    async get_order({ order_id }) {
      const order = await store.getOrder(order_id);
      if (!order) return { error: `Order not found: ${order_id}` };
      const days = order.deliveredDaysAgo ?? 0;
      return {
        order: { ...order, deliveredAt: deliveredDate(order) },
        daysSinceDelivery: days,
        returnWindowDays: RETURN_WINDOW_DAYS,
        withinReturnWindow: days <= RETURN_WINDOW_DAYS,
      };
    },
    async get_customer_history({ customer_id }) {
      const c = await store.getCustomer(customer_id);
      if (!c) return { error: `Customer not found: ${customer_id}` };
      const past = await store.casesByCustomer(customer_id);
      const rate = c.ordersLast12Months ? c.returnsLast12Months / c.ordersLast12Months : 0;
      return {
        customer: c,
        returnRateLast12Months: Math.round(rate * 100) + "%",
        returnsLast90Days: c.returnsLast90Days,
        previousCasesOnThisPlatform: past.map((p) => ({ caseId: p.caseId, sku: p.sku, status: p.status })),
      };
    },
    async check_inventory({ sku, size }) {
      const p = await store.getProduct(sku);
      if (!p) return { error: `Product not found: ${sku}` };
      return {
        sku: p.sku, name: p.name, category: p.category, price: p.price, fitNote: p.fitNote,
        requestedSize: size, requestedSizeInStock: p.stockBySize?.[size] ?? 0,
        sizesSmallestToLargest: p.sizes, allSizesInStock: p.stockBySize,
      };
    },
    async find_alternatives({ category, exclude_sku }) {
      const list = (await store.productsByCategory(category)).filter((p) => p.sku !== exclude_sku);
      return {
        category,
        alternatives: list.map((p) => ({
          sku: p.sku, name: p.name, price: p.price, fitNote: p.fitNote, sizes: p.sizes, stockBySize: p.stockBySize,
        })),
      };
    },
    async get_return_policy() {
      return { policy: POLICY };
    },
  };
  return async (name, args) => (tools[name] ? tools[name](args || {}) : { error: `Unknown tool: ${name}` });
}

// ---------------------------------------------------------------- agent loop
/**
 * @param startChat  () => chat with sendMessage(parts) -> { response: { functionCalls(): [{name,args}] } }
 * @param store      data access (see makeTools / guardrails)
 * @param request    { orderId, sku, message, photoBase64?, photoMimeType? }
 */
export async function runAgent({ startChat, store, request, engine }) {
  const order = await store.getOrder(request.orderId);
  if (!order) throw new Error(`Order not found: ${request.orderId}`);
  const item = order.items.find((i) => i.sku === request.sku);
  if (!item) throw new Error(`Item ${request.sku} is not on order ${order.orderId}`);

  const exec = makeTools(store);
  const trace = [];
  const chat = startChat();

  const userParts = [{
    text: `New return request.
Order: ${order.orderId} (customer ${order.customerId}, ${order.customerName})
Item: ${item.name}, SKU ${item.sku}, size ${item.size}, price ${item.price.toFixed(2)}
Customer message: "${request.message}"
Photo attached: ${request.photoBase64 ? "yes (below)" : "no"}`,
  }];
  if (request.photoBase64) {
    userParts.push({ inlineData: { mimeType: request.photoMimeType || "image/jpeg", data: request.photoBase64 } });
  }

  let result = await chat.sendMessage(userParts);
  let decision = null;

  for (let turn = 0; turn < MAX_TURNS && !decision; turn++) {
    const calls = result.response.functionCalls?.() || [];
    if (!calls.length) {
      trace.push({ type: "NOTE", name: "model", detail: "No tool call; asked the model to submit its decision." });
      result = await chat.sendMessage([{ text: "Please finish by calling submit_decision." }]);
      continue;
    }
    const responses = [];
    for (const call of calls) {
      const args = call.args || {};
      trace.push({ type: "TOOL_CALL", name: call.name, detail: JSON.stringify(args) });
      if (call.name === "submit_decision") {
        decision = toDecision(args);
        trace.push({ type: "DECISION", name: decision.resolution, detail: decision.internalRationale });
        break;
      }
      const out = await exec(call.name, args);
      trace.push({ type: "TOOL_RESULT", name: call.name, detail: abbreviate(JSON.stringify(out)) });
      responses.push({ functionResponse: { name: call.name, response: { result: out } } });
    }
    if (!decision) result = await chat.sendMessage(responses);
  }

  if (!decision) {
    decision = {
      resolution: "MANUAL_REVIEW", exchangeSku: null, exchangeSize: null, fraudRiskScore: 50,
      riskSignals: ["Agent did not reach a decision"], photoAssessment: null, reasonCategory: "OTHER",
      internalRationale: "Agent exhausted its turn budget; routed to a human.",
      customerReply: `Hi ${firstName(order.customerName)}, thanks for reaching out. A member of our team will review your return and get back to you within 24 hours.`,
      productInsight: null,
    };
  }

  decision = await applyGuardrails(decision, trace, store);
  const status = decision.resolution === "REJECT" ? "REJECTED"
    : decision.resolution === "MANUAL_REVIEW" ? "NEEDS_REVIEW" : "AUTO_APPROVED";

  const rc = {
    caseId: "RC-" + Math.random().toString(16).slice(2, 10).toUpperCase(),
    orderId: order.orderId, customerId: order.customerId, sku: item.sku, size: item.size, price: item.price,
    customerMessage: request.message, photoProvided: !!request.photoBase64, status, decision, trace,
    createdAt: new Date().toISOString(), engine,
  };
  await store.saveCase(rc);
  return rc;
}

function toDecision(a) {
  const s = (k) => (a[k] === undefined || a[k] === null || String(a[k]).trim() === "" ? null : String(a[k]));
  const score = Math.round(Number(a.fraud_risk_score ?? 0)) || 0;
  return {
    resolution: s("resolution"), exchangeSku: s("exchange_sku"), exchangeSize: s("exchange_size"),
    fraudRiskScore: Math.max(0, Math.min(100, score)),
    riskSignals: Array.isArray(a.risk_signals) ? a.risk_signals.map(String) : [],
    photoAssessment: s("photo_assessment"), reasonCategory: s("reason_category"),
    internalRationale: s("internal_rationale") || "", customerReply: s("customer_reply") || "",
    productInsight: s("product_insight"),
  };
}

// ---------------------------------------------------------------- guardrails
export async function applyGuardrails(d, trace, store) {
  const override = (resolution, why) => {
    trace.push({ type: "GUARDRAIL", name: "override:" + resolution, detail: why });
    return {
      ...d, resolution,
      internalRationale: `${d.internalRationale} [Guardrail: ${why}]`,
      customerReply: resolution === "MANUAL_REVIEW"
        ? "Thanks for your request. A member of our team will review it and get back to you within 24 hours."
        : d.customerReply,
    };
  };
  if (!d.resolution) return override("MANUAL_REVIEW", "Agent returned no resolution");
  if (d.fraudRiskScore >= MANUAL_REVIEW_THRESHOLD && AUTO_RESOLUTIONS.includes(d.resolution)) {
    return override("MANUAL_REVIEW", `Risk score ${d.fraudRiskScore} >= ${MANUAL_REVIEW_THRESHOLD}; auto-approval blocked`);
  }
  if (d.resolution === "REJECT" && d.fraudRiskScore >= MANUAL_REVIEW_THRESHOLD) {
    return override("MANUAL_REVIEW", "High-risk rejections must be confirmed by a human");
  }
  if (d.resolution === "EXCHANGE" || d.resolution === "SIZE_SWAP") {
    const ok = d.exchangeSku && d.exchangeSize && (await store.reserveStock(d.exchangeSku, d.exchangeSize));
    if (!ok) return override("MANUAL_REVIEW", `Could not reserve ${d.exchangeSku} size ${d.exchangeSize}`);
    trace.push({
      type: "GUARDRAIL", name: "stock-reserved",
      detail: `Reserved 1 x ${d.exchangeSku} size ${d.exchangeSize} (Firestore transaction)`,
    });
  }
  return d;
}

// ---------------------------------------------------------------- dashboard aggregates
export function insights(cases, prices) {
  const count = (f) => cases.reduce((m, c) => ((m[f(c)] = (m[f(c)] || 0) + 1), m), {});
  const kept = cases.filter((c) => ["EXCHANGE", "SIZE_SWAP", "STORE_CREDIT"].includes(c.decision.resolution));
  const seen = new Set();
  const productInsights = [];
  for (const c of cases) {
    const key = c.sku + "|" + c.decision.productInsight;
    if (c.decision.productInsight && !seen.has(key) && productInsights.length < 10) {
      seen.add(key);
      productInsights.push({ sku: c.sku, insight: c.decision.productInsight });
    }
  }
  return {
    totalCases: cases.length,
    byResolution: count((c) => c.decision.resolution),
    byReason: count((c) => String(c.decision.reasonCategory)),
    revenueRetainedValue: Math.round(kept.reduce((s, c) => s + (c.price ?? prices[c.sku] ?? 0), 0) * 100) / 100,
    averageRiskScore: cases.length ? Math.round(cases.reduce((s, c) => s + c.decision.fraudRiskScore, 0) / cases.length) : 0,
    needsReview: cases.filter((c) => c.status === "NEEDS_REVIEW").length,
    productInsights,
  };
}

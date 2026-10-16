// Firebase glue: Firestore as the system of record, Gemini via Firebase AI Logic.
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import {
  getFirestore, doc, getDoc, getDocs, setDoc, collection, query, where, orderBy, limit, runTransaction,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js";
import {
  getAI, getGenerativeModel, GoogleAIBackend, Schema, FunctionCallingMode,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-ai.js";
import { runAgent, toolDeclarations, SYSTEM_PROMPT, insights as computeInsights, isRateLimit } from "./agent.js";
import {
  simulatedHistory, withLiveCases, detectAnomalies, investigationInput, INVESTIGATION_PROMPT,
} from "./anomalies.js";

// First model is preferred. If a model is retired (404) it is dropped; if its free-tier
// quota is exhausted (429 after retries) the next model, which has its own quota, is tried.
const MODELS = ["gemini-3-flash-preview", "gemini-3.1-flash-lite"];

let ready;

export function init() {
  if (!ready) ready = boot();
  return ready;
}

async function boot() {
  // Firebase Hosting serves the project's web config at this reserved URL.
  const config = await fetch("/__/firebase/init.json").then((r) => {
    if (!r.ok) throw new Error("Firebase config not available");
    return r.json();
  });
  const app = initializeApp(config);
  const db = getFirestore(app);
  const ai = getAI(app, { backend: new GoogleAIBackend() });
  const tools = [{ functionDeclarations: toolDeclarations(Schema) }];

  const modelFor = (name) => getGenerativeModel(ai, {
    model: name,
    systemInstruction: SYSTEM_PROMPT,
    tools,
    toolConfig: { functionCallingConfig: { mode: FunctionCallingMode.ANY } },
    generationConfig: name.startsWith("gemini-3") ? {} : { temperature: 0.2 },
  });

  const store = {
    async getOrder(id) { return snap(await getDoc(doc(db, "orders", id))); },
    async getCustomer(id) { return snap(await getDoc(doc(db, "customers", id))); },
    async getProduct(sku) { return snap(await getDoc(doc(db, "products", sku))); },
    async productsByCategory(cat) {
      return (await getDocs(query(collection(db, "products"), where("category", "==", cat)))).docs.map((d) => d.data());
    },
    async casesByCustomer(id) {
      return (await getDocs(query(collection(db, "returnCases"), where("customerId", "==", id)))).docs.map((d) => d.data());
    },
    async recentCases(n = 100) {
      return (await getDocs(query(collection(db, "returnCases"), orderBy("createdAt", "desc"), limit(n)))).docs.map((d) => d.data());
    },
    async saveCase(rc) { await setDoc(doc(db, "returnCases", rc.caseId), rc); },
    // Stock is decremented in a transaction so two agents can never promise the same last unit.
    async reserveStock(sku, size) {
      const ref = doc(db, "products", sku);
      return runTransaction(db, async (tx) => {
        const s = await tx.get(ref);
        if (!s.exists()) return false;
        const stock = { ...s.data().stockBySize };
        if (!(stock[size] > 0)) return false;
        stock[size] -= 1;
        tx.update(ref, { stockBySize: stock });
        return true;
      });
    },
  };

  // Models that just hit their free-tier limit are skipped for a minute.
  const coolUntil = {};

  async function decide(request, onStatus) {
    const now = Date.now();
    const order = MODELS.filter((m) => !(coolUntil[m] > now));
    const candidates = order.length ? order : [...MODELS];
    for (let i = 0; i < candidates.length; i++) {
      const name = candidates[i];
      const last = i === candidates.length - 1;
      try {
        return await runAgent({
          startChat: () => modelFor(name).startChat(),
          store, request, onStatus, engine: `gemini:${name} (Firebase AI Logic)`,
          // Fail over to the next model at once; only the last model waits and retries.
          retryAttempts: last ? 3 : 1,
        });
      } catch (e) {
        const msg = String(e?.message || e);
        const retired = /\[404|status of 404|NOT_FOUND/i.test(msg);
        if (retired || isRateLimit(e)) {
          coolUntil[name] = Date.now() + (retired ? 24 * 3600e3 : 60e3);
          if (!last) {
            console.warn(`Model ${name} ${retired ? "unavailable" : "rate-limited"}; trying ${candidates[i + 1]}`);
            onStatus?.(`Gemini busy; switching to ${candidates[i + 1]}…`);
            continue;
          }
        }
        throw e;
      }
    }
    throw new Error("All Gemini models are busy. Please try again in a minute.");
  }

  async function dashboard() {
    const cases = await store.recentCases(200);
    const { rows, liveCasesAdded } = withLiveCases(simulatedHistory(), cases);
    return { cases, insights: computeInsights(cases, {}), anomalies: detectAnomalies(rows), liveCasesAdded };
  }

  // One Gemini call that explains every flagged anomaly. Structured JSON output, no tools.
  const investigationSchema = Schema.array({
    items: Schema.object({
      properties: {
        id: Schema.string(),
        likelyCause: Schema.string(),
        recommendedAction: Schema.string(),
        owner: Schema.string(),
        urgency: Schema.enumString({ enum: ["today", "this week", "monitor"] }),
      },
    }),
  });

  async function investigate(anomalies, cases) {
    const input = JSON.stringify(investigationInput(anomalies, cases));
    let lastError;
    for (const name of MODELS.filter((m) => !(coolUntil[m] > Date.now()))) {
      try {
        const model = getGenerativeModel(ai, {
          model: name,
          systemInstruction: INVESTIGATION_PROMPT,
          generationConfig: { responseMimeType: "application/json", responseSchema: investigationSchema },
        });
        const res = await model.generateContent(`Flagged anomalies:\n${input}`);
        const items = JSON.parse(res.response.text());
        return { model: name, items: Array.isArray(items) ? items : [] };
      } catch (e) {
        lastError = e;
        if (isRateLimit(e) || /\[404|NOT_FOUND/i.test(String(e?.message || e))) {
          coolUntil[name] = Date.now() + 60e3;
          continue;
        }
        throw e;
      }
    }
    throw lastError || new Error("Gemini is busy");
  }

  return {
    store, decide, dashboard, investigate,
    engineLabel: () => "Gemini 3 Flash · Firebase AI Logic · Firestore",
  };
}

function snap(s) {
  return s.exists() ? s.data() : null;
}

// Firebase glue: Firestore as the system of record, Gemini via Firebase AI Logic.
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import {
  getFirestore, doc, getDoc, getDocs, setDoc, collection, query, where, orderBy, limit, runTransaction,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js";
import {
  getAI, getGenerativeModel, GoogleAIBackend, Schema, FunctionCallingMode,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-ai.js";
import { runAgent, toolDeclarations, SYSTEM_PROMPT, insights as computeInsights, isRateLimit } from "./agent.js";

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

  let modelIndex = 0;
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

  async function decide(request, onStatus) {
    const start = modelIndex;
    for (let i = 0; i < MODELS.length; i++) {
      const idx = (start + i) % MODELS.length;
      const name = MODELS[idx];
      try {
        const rc = await runAgent({
          startChat: () => modelFor(name).startChat(),
          store, request, onStatus, engine: `gemini:${name} (Firebase AI Logic)`,
        });
        modelIndex = idx;
        return rc;
      } catch (e) {
        const msg = String(e?.message || e);
        const retired = /\[404|status of 404|NOT_FOUND/i.test(msg);
        if ((retired || isRateLimit(e)) && i + 1 < MODELS.length) {
          console.warn(`Model ${name} ${retired ? "unavailable" : "rate-limited"}; trying ${MODELS[(idx + 1) % MODELS.length]}`);
          onStatus?.(`Switching to ${MODELS[(idx + 1) % MODELS.length]}…`);
          continue;
        }
        throw e;
      }
    }
    throw new Error("All Gemini models are busy. Please try again in a minute.");
  }

  async function dashboard() {
    const cases = await store.recentCases(200);
    return { cases, insights: computeInsights(cases, {}) };
  }

  return {
    store, decide, dashboard,
    engineLabel: () => `Gemini ${MODELS[modelIndex]} · Firebase AI Logic · Firestore`,
  };
}

function snap(s) {
  return s.exists() ? s.data() : null;
}

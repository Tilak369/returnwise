// Seeds Firestore with the demo catalogue using your gcloud credentials (admin; bypasses rules).
// Usage (Cloud Shell):  node web/seed.mjs <project-id>
import { execSync } from "node:child_process";
import { products, customers, orders } from "./seed-data.js";

const project = process.argv[2] || execSync("gcloud config get-value project").toString().trim();
const token = execSync("gcloud auth print-access-token").toString().trim();
const base = `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents`;

const v = (x) => {
  if (x === null || x === undefined) return { nullValue: null };
  if (Array.isArray(x)) return { arrayValue: { values: x.map(v) } };
  if (typeof x === "boolean") return { booleanValue: x };
  if (typeof x === "number") return Number.isInteger(x) ? { integerValue: String(x) } : { doubleValue: x };
  if (typeof x === "object") return { mapValue: { fields: Object.fromEntries(Object.entries(x).map(([k, y]) => [k, v(y)])) } };
  return { stringValue: String(x) };
};

async function put(col, id, data) {
  const res = await fetch(`${base}/${col}/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", "x-goog-user-project": project },
    body: JSON.stringify({ fields: v(data).mapValue.fields }),
  });
  if (!res.ok) throw new Error(`${col}/${id}: ${res.status} ${await res.text()}`);
}

for (const p of products) await put("products", p.sku, p);
for (const c of customers) await put("customers", c.customerId, c);
for (const o of orders) await put("orders", o.orderId, o);
console.log(`Seeded ${products.length} products, ${customers.length} customers, ${orders.length} orders into ${project}`);

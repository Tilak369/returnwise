import { test } from "node:test";
import assert from "node:assert/strict";
import { simulatedHistory, withLiveCases, detectAnomalies, investigationInput, WEEKS } from "../anomalies.js";

const key = (a) => `${a.sku}|${a.reason}`;

test("finds the four patterns in the history and nothing else, for any seed", () => {
  const expected = ["LL-CT-WOOL-CAM|DEFECT", "LL-DR-WRAP-EMR|CHANGED_MIND", "LL-JN-SLIM-IND|SIZE_FIT", "LL-SH-LIN-SKY|NOT_AS_DESCRIBED"];
  for (const seed of [1, 7, 42, 20261016, 99991]) {
    assert.deepEqual(detectAnomalies(simulatedHistory(seed)).map(key).sort(), expected, `seed ${seed}`);
  }
});

test("each finding says where it is and comes with a rule-based next step", () => {
  const a = detectAnomalies(simulatedHistory());
  const coat = a.find((x) => x.sku === "LL-CT-WOOL-CAM");
  assert.equal(coat.dimension, "size");
  assert.equal(coat.value, "M");
  assert.equal(coat.suggested.owner, "Quality");
  const dress = a.find((x) => x.sku === "LL-DR-WRAP-EMR");
  assert.equal(dress.kind, "spike");
  assert.equal(dress.value, "SG");
  assert.equal(dress.weekly.length, WEEKS);
  assert.ok(a.every((x) => x.title && x.detail && x.suggested.action));
});

test("history is deterministic", () => {
  assert.deepEqual(simulatedHistory(5), simulatedHistory(5));
});

test("live cases are added once per order per day, only from the last 7 days", () => {
  const now = Date.parse("2026-10-16T12:00:00Z");
  const c = (id, at) => ({ orderId: id, sku: "LL-CT-WOOL-CAM", size: "M", createdAt: at, decision: { reasonCategory: "DEFECT" } });
  const { liveCasesAdded } = withLiveCases(simulatedHistory(), [
    c("LL-10022", "2026-10-16T08:00:00Z"), c("LL-10022", "2026-10-16T09:00:00Z"),
    c("LL-10022", "2026-10-15T09:00:00Z"), c("LL-10022", "2026-09-01T09:00:00Z"),
  ], now);
  assert.equal(liveCasesAdded, 2);
});

test("a normal store has no anomalies", () => {
  const flat = simulatedHistory().map((r) => ({ ...r, returns: { SIZE_FIT: 2, DEFECT: 0, CHANGED_MIND: 1, NOT_AS_DESCRIBED: 0 } }));
  assert.equal(detectAnomalies(flat).length, 0);
});

test("Gemini input carries product facts and real customer words", () => {
  const a = detectAnomalies(simulatedHistory());
  const input = investigationInput(a, [{ sku: "LL-CT-WOOL-CAM", customerMessage: "Seam split under the arm",
    decision: { reasonCategory: "DEFECT", productInsight: "Underarm seam is a weak point" } }]);
  const coat = input.find((x) => x.product.name === "Wool-Blend Overcoat");
  assert.deepEqual(coat.customerMessages, ["Seam split under the arm"]);
  assert.deepEqual(coat.agentProductInsights, ["Underarm seam is a weak point"]);
});

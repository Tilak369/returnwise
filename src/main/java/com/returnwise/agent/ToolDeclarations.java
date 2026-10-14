package com.returnwise.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Gemini function declarations (OpenAPI-style schemas) for the agent's tools. */
final class ToolDeclarations {

    private ToolDeclarations() {
    }

    static ArrayNode all(ObjectMapper m) {
        ArrayNode fns = m.createArrayNode();

        fns.add(fn(m, "get_order", "Fetch an order with delivery date, items and whether it is still within the return window.",
                props(m, "order_id", str(m, "Order id, e.g. LL-10021")), "order_id"));

        fns.add(fn(m, "get_customer_history", "Fetch the customer's loyalty tier, order and return counts, and earlier return cases.",
                props(m, "customer_id", str(m, "Customer id, e.g. C-1001")), "customer_id"));

        fns.add(fn(m, "check_inventory", "Check live stock for a SKU, including every size, and the product fit note.",
                props(m, "sku", str(m, "Product SKU"), "size", str(m, "Size to check")), "sku", "size"));

        fns.add(fn(m, "find_alternatives", "List other products in the same category with their stock by size.",
                props(m, "category", str(m, "Category, e.g. JEANS"), "exclude_sku", str(m, "SKU to leave out")), "category"));

        fns.add(fn(m, "get_return_policy", "Return the store's returns and exchange policy text.", props(m)));

        ObjectNode decision = props(m);
        decision.set("resolution", enumOf(m, "Final outcome",
                "REFUND", "EXCHANGE", "SIZE_SWAP", "STORE_CREDIT", "REJECT", "MANUAL_REVIEW"));
        decision.set("exchange_sku", str(m, "SKU to send as replacement (EXCHANGE or SIZE_SWAP only)"));
        decision.set("exchange_size", str(m, "Size to send as replacement (EXCHANGE or SIZE_SWAP only)"));
        decision.set("fraud_risk_score", m.createObjectNode().put("type", "INTEGER")
                .put("description", "0 = no risk, 100 = almost certainly abusive"));
        ObjectNode signals = m.createObjectNode().put("type", "ARRAY").put("description", "Short risk or trust signals observed");
        signals.set("items", m.createObjectNode().put("type", "STRING"));
        decision.set("risk_signals", signals);
        decision.set("photo_assessment", str(m, "What the photo shows (match, defect, wear), or 'No photo provided'"));
        decision.set("reason_category", enumOf(m, "Root cause of the return",
                "SIZE_FIT", "DEFECT", "NOT_AS_DESCRIBED", "CHANGED_MIND", "WRONG_ITEM", "OTHER"));
        decision.set("internal_rationale", str(m, "2-4 sentences for the ops team explaining the decision"));
        decision.set("customer_reply", str(m, "Message to the customer (max 80 words)"));
        decision.set("product_insight", str(m, "One actionable insight for merchandising or the product page"));
        fns.add(fn(m, "submit_decision", "Submit the final decision for this return. Call exactly once, last.",
                decision, "resolution", "fraud_risk_score", "reason_category", "internal_rationale", "customer_reply"));

        return fns;
    }

    private static ObjectNode fn(ObjectMapper m, String name, String description, ObjectNode properties, String... required) {
        ObjectNode fn = m.createObjectNode().put("name", name).put("description", description);
        if (properties.isEmpty()) {
            return fn; // no-argument tool
        }
        ObjectNode params = fn.putObject("parameters").put("type", "OBJECT");
        params.set("properties", properties);
        ArrayNode req = params.putArray("required");
        for (String r : required) {
            req.add(r);
        }
        return fn;
    }

    private static ObjectNode props(ObjectMapper m, Object... nameSchemaPairs) {
        ObjectNode n = m.createObjectNode();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) {
            n.set((String) nameSchemaPairs[i], (ObjectNode) nameSchemaPairs[i + 1]);
        }
        return n;
    }

    private static ObjectNode str(ObjectMapper m, String description) {
        return m.createObjectNode().put("type", "STRING").put("description", description);
    }

    private static ObjectNode enumOf(ObjectMapper m, String description, String... values) {
        ObjectNode n = str(m, description);
        ArrayNode e = n.putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        return n;
    }
}

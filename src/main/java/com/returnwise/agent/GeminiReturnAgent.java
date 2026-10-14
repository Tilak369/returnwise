package com.returnwise.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.returnwise.ai.GeminiClient;
import com.returnwise.model.Models.AgentStep;
import com.returnwise.model.Models.Decision;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agentic loop over Gemini function calling.
 * <p>
 * Gemini sees the customer's words and photo, decides which tools to call
 * (order, customer history, inventory, alternatives, policy), and finishes by
 * calling submit_decision with a structured outcome. Multimodal: the photo is
 * judged for damage, wear, and whether it actually shows the ordered item.
 */
public class GeminiReturnAgent implements ReturnAgent {

    private static final Logger log = LoggerFactory.getLogger(GeminiReturnAgent.class);
    private static final int MAX_TURNS = 8;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    static final String SYSTEM_PROMPT = """
            You are ReturnWise, the returns and exchange agent for Lumen & Loom, a fashion retailer.
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
            - Finish by calling submit_decision exactly once.
            """;

    private final GeminiClient gemini;
    private final AgentTools tools;
    private final ObjectMapper mapper;

    public GeminiReturnAgent(GeminiClient gemini, AgentTools tools, ObjectMapper mapper) {
        this.gemini = gemini;
        this.tools = tools;
        this.mapper = mapper;
    }

    @Override
    public AgentResult decide(ReturnRequest request) {
        Order order = tools.requireOrder(request.orderId());
        OrderItem item = order.items().stream().filter(i -> i.sku().equals(request.sku())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item " + request.sku() + " is not on order " + order.orderId()));

        List<AgentStep> trace = new ArrayList<>();
        ArrayNode contents = mapper.createArrayNode();
        contents.add(userTurn(request, order, item));

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            JsonNode response = gemini.generateContent(buildRequest(contents));
            JsonNode modelContent = response.path("candidates").path(0).path("content");
            if (modelContent.isMissingNode() || !modelContent.has("parts")) {
                trace.add(new AgentStep("NOTE", "empty-response", response.toString()));
                break;
            }
            contents.add(modelContent);

            ArrayNode functionResponses = mapper.createArrayNode();
            for (JsonNode part : modelContent.path("parts")) {
                if (part.has("text") && !part.path("text").asText().isBlank()) {
                    trace.add(new AgentStep("NOTE", "model", part.path("text").asText()));
                }
                JsonNode call = part.path("functionCall");
                if (call.isMissingNode()) {
                    continue;
                }
                String name = call.path("name").asText();
                Map<String, Object> args = mapper.convertValue(call.path("args"), MAP);
                trace.add(new AgentStep("TOOL_CALL", name, args == null ? "{}" : args.toString()));

                if ("submit_decision".equals(name)) {
                    Decision decision = toDecision(args);
                    trace.add(new AgentStep("DECISION", decision.resolution(), decision.internalRationale()));
                    return new AgentResult(decision, trace, "gemini:" + gemini.modelName());
                }

                Map<String, Object> result = tools.execute(name, args);
                trace.add(new AgentStep("TOOL_RESULT", name, abbreviate(writeJson(result))));
                ObjectNode fr = mapper.createObjectNode();
                fr.putObject("functionResponse")
                        .put("name", name)
                        .set("response", mapper.valueToTree(Map.of("result", result)));
                functionResponses.add(fr);
            }

            if (functionResponses.isEmpty()) {
                // Model answered in text without calling a tool; nudge it to submit.
                ObjectNode nudge = mapper.createObjectNode().put("role", "user");
                nudge.putArray("parts").addObject().put("text", "Please finish by calling submit_decision.");
                contents.add(nudge);
            } else {
                ObjectNode toolTurn = mapper.createObjectNode().put("role", "user");
                toolTurn.set("parts", functionResponses);
                contents.add(toolTurn);
            }
        }

        log.warn("Agent did not submit a decision for order {}", request.orderId());
        Decision fallback = new Decision("MANUAL_REVIEW", null, null, 50, List.of("Agent did not reach a decision"),
                null, "OTHER", "Agent exhausted its turn budget; routed to a human.",
                "Hi %s, thanks for reaching out. A member of our team will review your return and get back to you within 24 hours."
                        .formatted(firstName(order.customerName())),
                null);
        return new AgentResult(fallback, trace, "gemini:" + gemini.modelName());
    }

    private ObjectNode userTurn(ReturnRequest request, Order order, OrderItem item) {
        ObjectNode user = mapper.createObjectNode().put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.addObject().put("text", """
                New return request.
                Order: %s (customer %s, %s)
                Item: %s, SKU %s, size %s, price %.2f
                Customer message: "%s"
                Photo attached: %s
                """.formatted(order.orderId(), order.customerId(), order.customerName(), item.name(), item.sku(),
                item.size(), item.price(), request.customerMessage(), request.hasPhoto() ? "yes (below)" : "no"));
        if (request.hasPhoto()) {
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", request.photoMimeType() == null ? "image/jpeg" : request.photoMimeType());
            inline.put("data", request.photoBase64());
        }
        return user;
    }

    private ObjectNode buildRequest(ArrayNode contents) {
        ObjectNode req = mapper.createObjectNode();
        req.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_PROMPT);
        req.set("contents", contents);
        req.set("tools", mapper.createArrayNode().add(
                mapper.createObjectNode().set("functionDeclarations", ToolDeclarations.all(mapper))));
        req.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "ANY");
        req.putObject("generationConfig").put("temperature", 0.2);
        return req;
    }

    private Decision toDecision(Map<String, Object> a) {
        Object score = a.getOrDefault("fraud_risk_score", 0);
        @SuppressWarnings("unchecked")
        List<String> signals = a.get("risk_signals") instanceof List<?> l ? (List<String>) l : List.of();
        return new Decision(
                s(a, "resolution"),
                s(a, "exchange_sku"),
                s(a, "exchange_size"),
                (int) Math.round(Double.parseDouble(String.valueOf(score))),
                signals,
                s(a, "photo_assessment"),
                s(a, "reason_category"),
                s(a, "internal_rationale"),
                s(a, "customer_reply"),
                s(a, "product_insight"));
    }

    private static String s(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 700 ? s : s.substring(0, 700) + "…";
    }

    static String firstName(String fullName) {
        return fullName == null ? "there" : fullName.split(" ")[0];
    }
}

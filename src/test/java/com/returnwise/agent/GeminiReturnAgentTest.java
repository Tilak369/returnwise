package com.returnwise.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returnwise.ai.GeminiClient;
import com.returnwise.config.ReturnWiseProperties;
import com.returnwise.config.StoreConfig;
import com.returnwise.model.Models.ReturnCase;
import com.returnwise.service.ReturnService;
import com.returnwise.store.InMemoryStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full agent loop against scripted Gemini responses: tool calls are
 * executed against the store, results are sent back, and submit_decision ends the run.
 */
class GeminiReturnAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReturnWiseProperties props =
            new ReturnWiseProperties("demo-project", "global", "gemini-2.5-flash", "memory", "gemini", 30);
    private InMemoryStoreRepository store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStoreRepository();
        StoreConfig.seed(store);
    }

    @Test
    void sizeSwapFlowCallsToolsThenReservesStock() throws Exception {
        ScriptedGemini gemini = new ScriptedGemini(
                call("get_order", "{\"order_id\":\"LL-10021\"}"),
                call("check_inventory", "{\"sku\":\"LL-JN-SLIM-IND\",\"size\":\"33\"}"),
                call("submit_decision", """
                        {"resolution":"SIZE_SWAP","exchange_sku":"LL-JN-SLIM-IND","exchange_size":"33",
                         "fraud_risk_score":5,"risk_signals":["Loyal PLUS member"],"reason_category":"SIZE_FIT",
                         "internal_rationale":"Runs small; 33 in stock.","customer_reply":"Hi Priya, size 33 is on its way.",
                         "product_insight":"Add 'runs small' to the product page."}
                        """));
        ReturnService service = service(gemini);

        ReturnCase rc = service.process(new ReturnRequest("LL-10021", "LL-JN-SLIM-IND", "Too tight", null, null));

        assertThat(rc.status()).isEqualTo("AUTO_APPROVED");
        assertThat(rc.decision().resolution()).isEqualTo("SIZE_SWAP");
        assertThat(store.findProduct("LL-JN-SLIM-IND").orElseThrow().stockBySize().get("33")).isEqualTo(4);
        assertThat(rc.trace()).extracting(s -> s.type()).contains("TOOL_CALL", "TOOL_RESULT", "DECISION", "GUARDRAIL");
        // The tool result for get_order must have been sent back to Gemini as a functionResponse.
        assertThat(gemini.requests.get(1).toString()).contains("functionResponse").contains("withinReturnWindow");
        assertThat(gemini.requests.get(0).toString()).contains("systemInstruction").contains("submit_decision");
    }

    @Test
    void highRiskRefundIsForcedToManualReview() throws Exception {
        ScriptedGemini gemini = new ScriptedGemini(
                call("submit_decision", """
                        {"resolution":"REFUND","fraud_risk_score":85,"reason_category":"CHANGED_MIND",
                         "internal_rationale":"Serial returner.","customer_reply":"Refund approved."}
                        """));

        ReturnCase rc = service(gemini).process(new ReturnRequest("LL-10023", "LL-DR-WRAP-EMR", "Refund", null, null));

        assertThat(rc.decision().resolution()).isEqualTo("MANUAL_REVIEW");
        assertThat(rc.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(rc.decision().customerReply()).doesNotContain("Refund approved");
    }

    @Test
    void exchangeIntoOutOfStockSizeIsBlocked() throws Exception {
        ScriptedGemini gemini = new ScriptedGemini(
                call("submit_decision", """
                        {"resolution":"SIZE_SWAP","exchange_sku":"LL-JN-SLIM-IND","exchange_size":"32",
                         "fraud_risk_score":5,"reason_category":"SIZE_FIT",
                         "internal_rationale":"x","customer_reply":"y"}
                        """));

        ReturnCase rc = service(gemini).process(new ReturnRequest("LL-10021", "LL-JN-SLIM-IND", "Too tight", null, null));

        assertThat(rc.decision().resolution()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void photoIsSentAsInlineData() throws Exception {
        ScriptedGemini gemini = new ScriptedGemini(
                call("submit_decision", """
                        {"resolution":"REFUND","fraud_risk_score":10,"reason_category":"DEFECT",
                         "internal_rationale":"Seam split visible.","customer_reply":"Refund approved."}
                        """));

        service(gemini).process(new ReturnRequest("LL-10022", "LL-CT-WOOL-CAM", "Seam split", "aGVsbG8=", "image/jpeg"));

        assertThat(gemini.requests.get(0).toString()).contains("inlineData").contains("aGVsbG8=");
    }

    private ReturnService service(GeminiClient gemini) {
        AgentTools tools = new AgentTools(store, props);
        return new ReturnService(new GeminiReturnAgent(gemini, tools, mapper), store);
    }

    private JsonNode call(String name, String argsJson) throws Exception {
        String json = """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"%s","args":%s}}]}}]}
                """.formatted(name, argsJson);
        return mapper.readTree(json);
    }

    private class ScriptedGemini extends GeminiClient {
        final Deque<JsonNode> responses;
        final List<JsonNode> requests = new ArrayList<>();

        ScriptedGemini(JsonNode... responses) {
            super(props, mapper);
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public JsonNode generateContent(JsonNode request) {
            requests.add(request.deepCopy());
            return responses.removeFirst();
        }
    }
}

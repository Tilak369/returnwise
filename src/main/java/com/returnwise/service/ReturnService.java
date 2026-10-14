package com.returnwise.service;

import com.returnwise.agent.AgentResult;
import com.returnwise.agent.ReturnAgent;
import com.returnwise.agent.ReturnRequest;
import com.returnwise.model.Models.AgentStep;
import com.returnwise.model.Models.Decision;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.OrderItem;
import com.returnwise.model.Models.ReturnCase;
import com.returnwise.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the agent, then applies hard guardrails the model cannot override:
 * high-risk cases never auto-refund, and exchanges only go through if stock is
 * actually reserved in a Firestore transaction.
 */
@Service
public class ReturnService {

    static final int MANUAL_REVIEW_THRESHOLD = 70;
    private static final Set<String> AUTO_RESOLUTIONS = Set.of("REFUND", "EXCHANGE", "SIZE_SWAP", "STORE_CREDIT");

    private final ReturnAgent agent;
    private final StoreRepository store;

    public ReturnService(ReturnAgent agent, StoreRepository store) {
        this.agent = agent;
        this.store = store;
    }

    public ReturnCase process(ReturnRequest request) {
        Order order = store.findOrder(request.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + request.orderId()));
        OrderItem item = order.items().stream().filter(i -> i.sku().equals(request.sku())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item not on this order"));

        AgentResult result = agent.decide(request);
        List<AgentStep> trace = new ArrayList<>(result.trace());
        Decision decision = applyGuardrails(result.decision(), trace);

        String status = switch (decision.resolution()) {
            case "REJECT" -> "REJECTED";
            case "MANUAL_REVIEW" -> "NEEDS_REVIEW";
            default -> "AUTO_APPROVED";
        };

        ReturnCase rc = new ReturnCase(
                "RC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                order.orderId(), order.customerId(), item.sku(), item.size(),
                request.customerMessage(), request.hasPhoto(), status, decision, trace,
                Instant.now().toString(), result.engine());
        store.saveCase(rc);
        return rc;
    }

    Decision applyGuardrails(Decision d, List<AgentStep> trace) {
        if (d.resolution() == null) {
            return withResolution(d, "MANUAL_REVIEW", trace, "Agent returned no resolution");
        }
        if (d.fraudRiskScore() >= MANUAL_REVIEW_THRESHOLD && AUTO_RESOLUTIONS.contains(d.resolution())) {
            return withResolution(d, "MANUAL_REVIEW", trace,
                    "Risk score " + d.fraudRiskScore() + " >= " + MANUAL_REVIEW_THRESHOLD + "; auto-approval blocked");
        }
        if ("REJECT".equals(d.resolution()) && d.fraudRiskScore() >= MANUAL_REVIEW_THRESHOLD) {
            return withResolution(d, "MANUAL_REVIEW", trace, "High-risk rejections must be confirmed by a human");
        }
        if (("EXCHANGE".equals(d.resolution()) || "SIZE_SWAP".equals(d.resolution()))) {
            if (d.exchangeSku() == null || d.exchangeSize() == null
                    || !store.reserveStock(d.exchangeSku(), d.exchangeSize())) {
                return withResolution(d, "MANUAL_REVIEW", trace,
                        "Could not reserve " + d.exchangeSku() + " size " + d.exchangeSize());
            }
            trace.add(new AgentStep("GUARDRAIL", "stock-reserved",
                    "Reserved 1 x " + d.exchangeSku() + " size " + d.exchangeSize() + " (Firestore transaction)"));
        }
        return d;
    }

    private Decision withResolution(Decision d, String resolution, List<AgentStep> trace, String why) {
        trace.add(new AgentStep("GUARDRAIL", "override:" + resolution, why));
        String reply = "MANUAL_REVIEW".equals(resolution)
                ? "Thanks for your request. A member of our team will review it and get back to you within 24 hours."
                : d.customerReply();
        return new Decision(resolution, d.exchangeSku(), d.exchangeSize(), d.fraudRiskScore(), d.riskSignals(),
                d.photoAssessment(), d.reasonCategory(), d.internalRationale() + " [Guardrail: " + why + "]",
                reply, d.productInsight());
    }
}

package com.returnwise.service;

import com.returnwise.agent.AgentResult;
import com.returnwise.agent.ReturnAgent;
import com.returnwise.agent.ReturnRequest;
import com.returnwise.config.ReturnWiseProperties;
import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.AgentStep;
import com.returnwise.model.Models.Decision;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.OrderItem;
import com.returnwise.model.Models.ReturnCase;
import com.returnwise.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the agent, then applies hard guardrails the model cannot override:
 * high-risk cases and serial returners never auto-resolve, late non-faulty returns
 * are never auto-approved, and exchanges only go through if stock is actually
 * reserved in a Firestore transaction. Same rules as the web prototype (web/agent.js).
 */
@Service
public class ReturnService {

    static final int MANUAL_REVIEW_THRESHOLD = 70;
    static final double SERIAL_RETURN_RATE = 0.5;
    static final int SERIAL_RETURNS_90_DAYS = 5;
    private static final Set<String> AUTO_RESOLUTIONS = Set.of("REFUND", "EXCHANGE", "SIZE_SWAP", "STORE_CREDIT");

    private final ReturnAgent agent;
    private final StoreRepository store;
    private final ReturnWiseProperties props;

    public ReturnService(ReturnAgent agent, StoreRepository store, ReturnWiseProperties props) {
        this.agent = agent;
        this.store = store;
        this.props = props;
    }

    /** Facts the guardrails need, looked up by the server rather than taken from the model. */
    record GuardrailContext(boolean withinReturnWindow, Customer customer, OrderItem item) {
    }

    public ReturnCase process(ReturnRequest request) {
        Order order = store.findOrder(request.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + request.orderId()));
        OrderItem item = order.items().stream().filter(i -> i.sku().equals(request.sku())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item not on this order"));

        AgentResult result = agent.decide(request);
        List<AgentStep> trace = new ArrayList<>(result.trace());
        long days = ChronoUnit.DAYS.between(LocalDate.parse(order.deliveredAt()), LocalDate.now());
        GuardrailContext ctx = new GuardrailContext(days <= props.returnWindowDays(),
                store.findCustomer(order.customerId()).orElse(null), item);
        Decision decision = applyGuardrails(result.decision(), trace, ctx);

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

    Decision applyGuardrails(Decision d, List<AgentStep> trace, GuardrailContext ctx) {
        if (d.resolution() == null) {
            return withResolution(d, "MANUAL_REVIEW", trace, "Agent returned no resolution");
        }
        if (d.fraudRiskScore() >= MANUAL_REVIEW_THRESHOLD && AUTO_RESOLUTIONS.contains(d.resolution())) {
            return withResolution(d, "MANUAL_REVIEW", trace,
                    "Risk score " + d.fraudRiskScore() + " >= " + MANUAL_REVIEW_THRESHOLD + "; auto-approval blocked");
        }
        Customer c = ctx.customer();
        double rate = c == null || c.ordersLast12Months() == 0 ? 0 : (double) c.returnsLast12Months() / c.ordersLast12Months();
        if (c != null && AUTO_RESOLUTIONS.contains(d.resolution())
                && rate >= SERIAL_RETURN_RATE && c.returnsLast90Days() >= SERIAL_RETURNS_90_DAYS) {
            return withResolution(d, "MANUAL_REVIEW", trace, "Serial-return pattern (" + Math.round(rate * 100)
                    + "% return rate, " + c.returnsLast90Days() + " returns in 90 days); a person must approve this, "
                    + "whatever the AI's score");
        }
        if ("REJECT".equals(d.resolution()) && d.fraudRiskScore() >= MANUAL_REVIEW_THRESHOLD) {
            return withResolution(d, "MANUAL_REVIEW", trace, "High-risk rejections must be confirmed by a human");
        }
        if (!ctx.withinReturnWindow() && !"DEFECT".equals(d.reasonCategory())
                && Set.of("REFUND", "EXCHANGE", "SIZE_SWAP").contains(d.resolution())) {
            if (c != null && "PLUS".equals(c.tier())) {
                Decision credit = withResolution(d, "STORE_CREDIT", trace,
                        "Outside the return window; policy allows store credit for PLUS members only");
                return new Decision(credit.resolution(), credit.exchangeSku(), credit.exchangeSize(),
                        credit.fraudRiskScore(), credit.riskSignals(), credit.photoAssessment(), credit.reasonCategory(),
                        credit.internalRationale(), "Thanks for getting in touch. This order is past our "
                        + props.returnWindowDays() + "-day return window, but as a PLUS member we can offer you store "
                        + "credit for it. We'll email a prepaid label shortly.", credit.productInsight());
            }
            return withResolution(d, "MANUAL_REVIEW", trace,
                    "Outside the return window and not faulty; the agent may not approve this on its own");
        }
        boolean replacement = "EXCHANGE".equals(d.resolution()) || "SIZE_SWAP".equals(d.resolution());
        if (replacement && ctx.item() != null && (d.exchangeSku() == null || d.exchangeSize() == null)) {
            // Gemini chose a replacement but left out what to send: default to like-for-like.
            d = new Decision(d.resolution(), d.exchangeSku() != null ? d.exchangeSku() : ctx.item().sku(),
                    d.exchangeSize() != null ? d.exchangeSize() : ctx.item().size(), d.fraudRiskScore(),
                    d.riskSignals(), d.photoAssessment(), d.reasonCategory(), d.internalRationale(),
                    d.customerReply(), d.productInsight());
            trace.add(new AgentStep("GUARDRAIL", "like-for-like", "No replacement specified; using the ordered item "
                    + d.exchangeSku() + " size " + d.exchangeSize()));
        }
        if (replacement) {
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

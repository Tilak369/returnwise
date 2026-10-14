package com.returnwise.agent;

import com.returnwise.model.Models.AgentStep;
import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Decision;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.OrderItem;
import com.returnwise.model.Models.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based stand-in used ONLY for local development without Google Cloud credentials
 * (returnwise.ai-mode=offline). It calls the same tools as the Gemini agent so the UI and
 * persistence can be exercised offline. Deployed builds use {@link GeminiReturnAgent}.
 */
public class OfflineRulesAgent implements ReturnAgent {

    private final AgentTools tools;

    public OfflineRulesAgent(AgentTools tools) {
        this.tools = tools;
    }

    @Override
    public AgentResult decide(ReturnRequest request) {
        List<AgentStep> trace = new ArrayList<>();
        trace.add(new AgentStep("NOTE", "offline-mode", "Rule-based local stand-in; Gemini is used when deployed."));

        Map<String, Object> orderInfo = tools.getOrder(request.orderId());
        trace.add(new AgentStep("TOOL_CALL", "get_order", "{order_id=" + request.orderId() + "}"));
        Order order = tools.requireOrder(request.orderId());
        OrderItem item = order.items().stream().filter(i -> i.sku().equals(request.sku())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item not on order"));
        boolean inWindow = Boolean.TRUE.equals(orderInfo.get("withinReturnWindow"));

        trace.add(new AgentStep("TOOL_CALL", "get_customer_history", "{customer_id=" + order.customerId() + "}"));
        Customer customer = tools.customer(order.customerId());

        trace.add(new AgentStep("TOOL_CALL", "check_inventory", "{sku=" + item.sku() + ", size=" + item.size() + "}"));
        Product product = tools.product(item.sku());

        String msg = request.customerMessage() == null ? "" : request.customerMessage().toLowerCase(Locale.ROOT);
        String reason = msg.matches(".*(small|tight|big|large|loose|size|fit).*") ? "SIZE_FIT"
                : msg.matches(".*(tear|torn|hole|broken|defect|stain|seam|damaged|faulty).*") ? "DEFECT"
                : msg.matches(".*(wrong|different item).*") ? "WRONG_ITEM"
                : "CHANGED_MIND";

        int risk = 10;
        List<String> signals = new ArrayList<>();
        if (customer != null && customer.returnsLast90Days() >= 4) {
            risk += 45;
            signals.add(customer.returnsLast90Days() + " returns in the last 90 days");
        }
        if (item.price() >= 150 && "DRESSES".equals(product.category())) {
            risk += 20;
            signals.add("High-value occasion wear");
        }
        if (!inWindow) {
            signals.add("Outside return window");
        }
        if (customer != null && "PLUS".equals(customer.tier()) && customer.returnsLast90Days() == 0) {
            risk = Math.max(0, risk - 5);
            signals.add("Loyal PLUS member with low return rate");
        }

        String first = GeminiReturnAgent.firstName(order.customerName());
        Decision d;
        if (risk >= 60) {
            d = new Decision("MANUAL_REVIEW", null, null, risk, signals, photo(request), reason,
                    "Risk signals exceed the auto-approval threshold; routing to a human.",
                    "Hi %s, thanks for your request. Our team will review it and reply within 24 hours.".formatted(first),
                    null);
        } else if ("DEFECT".equals(reason)) {
            d = new Decision("REFUND", null, null, risk, signals, photo(request), reason,
                    "Customer reports a defect within policy; full refund.",
                    "Hi %s, sorry about that. We've approved a full refund and will email a free return label.".formatted(first),
                    "Check quality on " + item.name() + ".");
        } else if (!inWindow) {
            boolean plus = customer != null && "PLUS".equals(customer.tier());
            d = new Decision(plus ? "STORE_CREDIT" : "REJECT", null, null, risk, signals, photo(request), reason,
                    "Outside the return window.",
                    "Hi %s, this order is outside our return window, so we can't take it back this time.".formatted(first),
                    null);
        } else if ("SIZE_FIT".equals(reason) && product != null) {
            // Sizes are stored smallest to largest; step up for "tight/small", down for "big/loose".
            List<String> sizes = new ArrayList<>(product.stockBySize().keySet());
            int idx = sizes.indexOf(item.size());
            int step = msg.matches(".*(tight|small|bigger|larger).*") ? 1
                    : msg.matches(".*(big|large|loose).*") ? -1 : 1;
            String target = null;
            for (int i = idx + step; i >= 0 && i < sizes.size(); i += step) {
                if (product.stockBySize().getOrDefault(sizes.get(i), 0) > 0) {
                    target = sizes.get(i);
                    break;
                }
            }
            d = target == null
                    ? new Decision("REFUND", null, null, risk, signals, photo(request), reason,
                    "No alternative size in stock.", "Hi %s, we've approved your refund.".formatted(first), product.fitNote())
                    : new Decision("SIZE_SWAP", item.sku(), target, risk, signals, photo(request), reason,
                    "Size issue with stock available; swap preferred over refund.",
                    "Hi %s, we're sending size %s right away. Return the original with the prepaid label.".formatted(first, target),
                    product.fitNote());
        } else {
            d = new Decision("REFUND", null, null, risk, signals, photo(request), reason,
                    "Within window, low risk.", "Hi %s, your refund is approved.".formatted(first), null);
        }
        trace.add(new AgentStep("DECISION", d.resolution(), d.internalRationale()));
        return new AgentResult(d, trace, "offline-rules");
    }

    private static String photo(ReturnRequest r) {
        return r.hasPhoto() ? "Photo received (not analysed in offline mode)" : "No photo provided";
    }
}

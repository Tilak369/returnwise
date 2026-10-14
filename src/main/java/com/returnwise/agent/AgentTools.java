package com.returnwise.agent;

import com.returnwise.config.ReturnWiseProperties;
import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.Product;
import com.returnwise.model.Models.ReturnCase;
import com.returnwise.store.StoreRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools Gemini can call. Each one is deterministic business logic over Firestore data:
 * the model decides WHICH facts it needs and how to weigh them; the facts themselves
 * always come from the system of record, never from the model.
 */
@Component
public class AgentTools {

    public static final String POLICY = """
            Lumen & Loom returns policy:
            1. Online orders can be returned or exchanged within %d days of delivery.
            2. Items must be unworn, unwashed and have tags attached, unless the item is faulty.
            3. Faulty or damaged items: full refund or like-for-like exchange, any time within 90 days.
            4. Size exchanges are free and preferred when the requested size is in stock.
            5. Returns after the window may be offered store credit at the business's discretion for loyal (PLUS) members.
            6. Cases with high fraud risk (worn items, serial returns of occasion wear, photo not matching the item)
               must go to MANUAL_REVIEW rather than being rejected automatically.
            """;

    private final StoreRepository store;
    private final ReturnWiseProperties props;

    public AgentTools(StoreRepository store, ReturnWiseProperties props) {
        this.store = store;
        this.props = props;
    }

    public Map<String, Object> execute(String name, Map<String, Object> args) {
        return switch (name) {
            case "get_order" -> getOrder(str(args, "order_id"));
            case "get_customer_history" -> getCustomerHistory(str(args, "customer_id"));
            case "check_inventory" -> checkInventory(str(args, "sku"), str(args, "size"));
            case "find_alternatives" -> findAlternatives(str(args, "category"), str(args, "exclude_sku"));
            case "get_return_policy" -> Map.of("policy", POLICY.formatted(props.returnWindowDays()));
            default -> Map.of("error", "Unknown tool: " + name);
        };
    }

    public Map<String, Object> getOrder(String orderId) {
        return store.findOrder(orderId).<Map<String, Object>>map(order -> {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(order.deliveredAt()), LocalDate.now());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order", order);
            out.put("daysSinceDelivery", days);
            out.put("returnWindowDays", props.returnWindowDays());
            out.put("withinReturnWindow", days <= props.returnWindowDays());
            return out;
        }).orElse(Map.of("error", "Order not found: " + orderId));
    }

    public Map<String, Object> getCustomerHistory(String customerId) {
        return store.findCustomer(customerId).<Map<String, Object>>map(c -> {
            List<ReturnCase> past = store.findCasesByCustomer(customerId);
            double returnRate = c.ordersLast12Months() == 0 ? 0 : (double) c.returnsLast12Months() / c.ordersLast12Months();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("customer", c);
            out.put("returnRateLast12Months", Math.round(returnRate * 100) + "%");
            out.put("returnsLast90Days", c.returnsLast90Days());
            out.put("previousCasesOnThisPlatform", past.stream()
                    .map(rc -> Map.of("caseId", rc.caseId(), "sku", rc.sku(), "status", rc.status()))
                    .toList());
            return out;
        }).orElse(Map.of("error", "Customer not found: " + customerId));
    }

    public Map<String, Object> checkInventory(String sku, String size) {
        return store.findProduct(sku).<Map<String, Object>>map(p -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sku", p.sku());
            out.put("name", p.name());
            out.put("category", p.category());
            out.put("price", p.price());
            out.put("fitNote", p.fitNote());
            out.put("requestedSize", size);
            out.put("requestedSizeInStock", p.stockBySize().getOrDefault(size, 0));
            out.put("allSizesInStock", p.stockBySize());
            return out;
        }).orElse(Map.of("error", "Product not found: " + sku));
    }

    public Map<String, Object> findAlternatives(String category, String excludeSku) {
        List<Map<String, Object>> alternatives = store.findProductsByCategory(category).stream()
                .filter(p -> !p.sku().equals(excludeSku))
                .map(p -> Map.<String, Object>of(
                        "sku", p.sku(), "name", p.name(), "price", p.price(),
                        "fitNote", p.fitNote(), "stockBySize", p.stockBySize()))
                .toList();
        return Map.of("category", category, "alternatives", alternatives);
    }

    public Order requireOrder(String orderId) {
        return store.findOrder(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    public Customer customer(String customerId) {
        return store.findCustomer(customerId).orElse(null);
    }

    public Product product(String sku) {
        return store.findProduct(sku).orElse(null);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}

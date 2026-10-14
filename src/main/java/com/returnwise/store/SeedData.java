package com.returnwise.store;

import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.OrderItem;
import com.returnwise.model.Models.Product;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo catalogue for a fictional fashion retailer, "Lumen &amp; Loom".
 * Delivery dates are relative to today so the return-window logic always demos the same way.
 */
public final class SeedData {

    private SeedData() {
    }

    public static List<Product> products() {
        return List.of(
                new Product("LL-JN-SLIM-IND", "Slim Fit Jeans", "JEANS", "Indigo", 59.90,
                        "Stretch cotton denim, slim through hip and thigh, tapered leg.",
                        sizes("28", 4, "30", 6, "32", 0, "33", 5, "34", 8, "36", 3),
                        "Customers report this style runs about one size small in the waist."),
                new Product("LL-JN-REG-IND", "Regular Fit Jeans", "JEANS", "Indigo", 59.90,
                        "Rigid cotton denim, straight regular fit.",
                        sizes("30", 5, "32", 7, "34", 6, "36", 2),
                        "True to size."),
                new Product("LL-CT-WOOL-CAM", "Wool-Blend Overcoat", "OUTERWEAR", "Camel", 249.00,
                        "Double-faced wool blend, fully lined, horn-effect buttons.",
                        sizes("S", 3, "M", 4, "L", 2, "XL", 1),
                        "True to size; relaxed shoulder."),
                new Product("LL-DR-WRAP-EMR", "Satin Wrap Dress", "DRESSES", "Emerald", 189.00,
                        "Occasion-wear satin wrap dress with tie waist.",
                        sizes("XS", 2, "S", 3, "M", 1, "L", 2),
                        "True to size."),
                new Product("LL-SN-RUN-WHT", "Court Sneakers", "FOOTWEAR", "White", 89.00,
                        "Leather-look court sneakers with cushioned insole.",
                        sizes("40", 3, "41", 4, "42", 5, "43", 2, "44", 1),
                        "Runs half a size large."),
                new Product("LL-SH-LIN-SKY", "Linen Shirt", "SHIRTS", "Sky Blue", 45.00,
                        "Pure linen, relaxed fit, button-down collar.",
                        sizes("S", 6, "M", 9, "L", 7, "XL", 4),
                        "Relaxed fit; size down for a closer fit.")
        );
    }

    public static List<Customer> customers() {
        return List.of(
                new Customer("C-1001", "Priya Raman", "PLUS", 18, 2, 0, 1420.50,
                        List.of("Loyal member since 2021", "Returns are rare and usually size related")),
                new Customer("C-1002", "Daniel Tan", "MEMBER", 6, 1, 0, 610.00,
                        List.of()),
                new Customer("C-2001", "Alex Morgan", "MEMBER", 14, 11, 7, 2210.00,
                        List.of("High return rate on occasion wear", "Several returns of worn items flagged by store staff"))
        );
    }

    public static List<Order> orders() {
        LocalDate today = LocalDate.now();
        return List.of(
                order("LL-10021", "C-1001", "Priya Raman", today.minusDays(9), today.minusDays(6),
                        new OrderItem("LL-JN-SLIM-IND", "Slim Fit Jeans", "32", 1, 59.90)),
                order("LL-10022", "C-1002", "Daniel Tan", today.minusDays(14), today.minusDays(10),
                        new OrderItem("LL-CT-WOOL-CAM", "Wool-Blend Overcoat", "M", 1, 249.00)),
                order("LL-10023", "C-2001", "Alex Morgan", today.minusDays(30), today.minusDays(26),
                        new OrderItem("LL-DR-WRAP-EMR", "Satin Wrap Dress", "S", 1, 189.00)),
                order("LL-10024", "C-1002", "Daniel Tan", today.minusDays(45), today.minusDays(41),
                        new OrderItem("LL-SN-RUN-WHT", "Court Sneakers", "42", 1, 89.00)),
                order("LL-10025", "C-1001", "Priya Raman", today.minusDays(5), today.minusDays(3),
                        new OrderItem("LL-SH-LIN-SKY", "Linen Shirt", "M", 1, 45.00))
        );
    }

    private static Order order(String id, String customerId, String name, LocalDate placed, LocalDate delivered,
                               OrderItem item) {
        return new Order(id, customerId, name, placed.toString(), delivered.toString(), "ONLINE", List.of(item));
    }

    private static Map<String, Integer> sizes(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}

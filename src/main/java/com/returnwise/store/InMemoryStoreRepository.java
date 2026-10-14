package com.returnwise.store;

import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.Product;
import com.returnwise.model.Models.ReturnCase;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local-development store. Not used on Cloud Run, where Firestore is the source of truth. */
public class InMemoryStoreRepository implements StoreRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, ReturnCase> cases = new ConcurrentHashMap<>();

    @Override
    public Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Optional<Customer> findCustomer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    @Override
    public Optional<Product> findProduct(String sku) {
        return Optional.ofNullable(products.get(sku));
    }

    @Override
    public List<Product> findProductsByCategory(String category) {
        return products.values().stream().filter(p -> p.category().equalsIgnoreCase(category)).toList();
    }

    @Override
    public List<ReturnCase> findCasesByCustomer(String customerId) {
        return cases.values().stream().filter(c -> customerId.equals(c.customerId())).toList();
    }

    @Override
    public void saveCase(ReturnCase returnCase) {
        cases.put(returnCase.caseId(), returnCase);
    }

    @Override
    public Optional<ReturnCase> findCase(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    @Override
    public List<ReturnCase> recentCases(int limit) {
        return cases.values().stream()
                .sorted(Comparator.comparing(ReturnCase::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean reserveStock(String sku, String size) {
        Product p = products.get(sku);
        if (p == null) {
            return false;
        }
        int stock = p.stockBySize().getOrDefault(size, 0);
        if (stock <= 0) {
            return false;
        }
        Map<String, Integer> updated = new LinkedHashMap<>(p.stockBySize());
        updated.put(size, stock - 1);
        products.put(sku, new Product(p.sku(), p.name(), p.category(), p.color(), p.price(), p.description(),
                updated, p.fitNote()));
        return true;
    }

    @Override
    public void saveProduct(Product product) {
        products.put(product.sku(), product);
    }

    @Override
    public void saveOrder(Order order) {
        orders.put(order.orderId(), order);
    }

    @Override
    public void saveCustomer(Customer customer) {
        customers.put(customer.customerId(), customer);
    }

    @Override
    public boolean isEmpty() {
        return products.isEmpty();
    }
}

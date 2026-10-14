package com.returnwise.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.Product;
import com.returnwise.model.Models.ReturnCase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Firestore-backed store. Collections: products, orders, customers, returnCases.
 * Stock reservation for exchanges runs in a Firestore transaction so two agents
 * can never promise the same last unit.
 */
public class FirestoreStoreRepository implements StoreRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final Firestore db;
    private final ObjectMapper mapper;

    public FirestoreStoreRepository(Firestore db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        return get("orders", orderId, Order.class);
    }

    @Override
    public Optional<Customer> findCustomer(String customerId) {
        return get("customers", customerId, Customer.class);
    }

    @Override
    public Optional<Product> findProduct(String sku) {
        return get("products", sku, Product.class);
    }

    @Override
    public List<Product> findProductsByCategory(String category) {
        return query(db.collection("products").whereEqualTo("category", category), Product.class);
    }

    @Override
    public List<ReturnCase> findCasesByCustomer(String customerId) {
        return query(db.collection("returnCases").whereEqualTo("customerId", customerId), ReturnCase.class);
    }

    @Override
    public void saveCase(ReturnCase returnCase) {
        set("returnCases", returnCase.caseId(), returnCase);
    }

    @Override
    public Optional<ReturnCase> findCase(String caseId) {
        return get("returnCases", caseId, ReturnCase.class);
    }

    @Override
    public List<ReturnCase> recentCases(int limit) {
        return query(db.collection("returnCases").orderBy("createdAt", Query.Direction.DESCENDING).limit(limit),
                ReturnCase.class);
    }

    @Override
    public boolean reserveStock(String sku, String size) {
        DocumentReference ref = db.collection("products").document(sku);
        try {
            return db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) {
                    return false;
                }
                Product p = mapper.convertValue(snap.getData(), Product.class);
                int stock = p.stockBySize().getOrDefault(size, 0);
                if (stock <= 0) {
                    return false;
                }
                Map<String, Object> stockUpdate = new LinkedHashMap<>(p.stockBySize());
                stockUpdate.put(size, stock - 1);
                tx.update(ref, "stockBySize", stockUpdate);
                return true;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reserving stock", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to reserve stock for " + sku + "/" + size, e);
        }
    }

    @Override
    public void saveProduct(Product product) {
        set("products", product.sku(), product);
    }

    @Override
    public void saveOrder(Order order) {
        set("orders", order.orderId(), order);
    }

    @Override
    public void saveCustomer(Customer customer) {
        set("customers", customer.customerId(), customer);
    }

    @Override
    public boolean isEmpty() {
        try {
            return db.collection("products").limit(1).get().get().isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore unavailable", e);
        }
    }

    private <T> Optional<T> get(String collection, String id, Class<T> type) {
        try {
            DocumentSnapshot snap = db.collection(collection).document(id).get().get();
            return snap.exists() ? Optional.of(mapper.convertValue(snap.getData(), type)) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read " + collection + "/" + id, e);
        }
    }

    private <T> List<T> query(Query query, Class<T> type) {
        try {
            return query.get().get().getDocuments().stream()
                    .map(d -> mapper.convertValue(d.getData(), type))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore query failed", e);
        }
    }

    private void set(String collection, String id, Object value) {
        try {
            db.collection(collection).document(id).set(mapper.convertValue(value, MAP)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to write " + collection + "/" + id, e);
        }
    }
}

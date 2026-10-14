package com.returnwise.store;

import com.returnwise.model.Models.Customer;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.Product;
import com.returnwise.model.Models.ReturnCase;

import java.util.List;
import java.util.Optional;

/** Persistence port. Firestore in the cloud, in-memory for local development. */
public interface StoreRepository {

    Optional<Order> findOrder(String orderId);

    Optional<Customer> findCustomer(String customerId);

    Optional<Product> findProduct(String sku);

    List<Product> findProductsByCategory(String category);

    List<ReturnCase> findCasesByCustomer(String customerId);

    void saveCase(ReturnCase returnCase);

    Optional<ReturnCase> findCase(String caseId);

    List<ReturnCase> recentCases(int limit);

    /** Reserve one unit of stock for an exchange. Returns false if none left. */
    boolean reserveStock(String sku, String size);

    void saveProduct(Product product);

    void saveOrder(Order order);

    void saveCustomer(Customer customer);

    boolean isEmpty();
}

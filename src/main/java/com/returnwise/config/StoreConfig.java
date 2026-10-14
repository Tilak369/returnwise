package com.returnwise.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.FirestoreOptions;
import com.returnwise.store.FirestoreStoreRepository;
import com.returnwise.store.InMemoryStoreRepository;
import com.returnwise.store.SeedData;
import com.returnwise.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreConfig.class);

    @Bean
    public StoreRepository storeRepository(ReturnWiseProperties props, ObjectMapper mapper) {
        if (props.useFirestore()) {
            FirestoreOptions.Builder options = FirestoreOptions.getDefaultInstance().toBuilder();
            if (props.projectId() != null && !props.projectId().isBlank()) {
                options.setProjectId(props.projectId());
            }
            log.info("Using Firestore store (project={})", props.projectId());
            return new FirestoreStoreRepository(options.build().getService(), mapper);
        }
        log.warn("Using IN-MEMORY store - for local development only");
        return new InMemoryStoreRepository();
    }

    /** Loads the demo catalogue the first time the app starts against an empty database. */
    @Bean
    public ApplicationRunner seedOnStartup(StoreRepository store) {
        return args -> {
            if (store.isEmpty()) {
                SeedData.products().forEach(store::saveProduct);
                log.info("Seeded demo catalogue");
            }
            // Demo orders use dates relative to today, so refresh them on every start;
            // this keeps the return-window scenarios stable throughout the judging period.
            SeedData.customers().forEach(store::saveCustomer);
            SeedData.orders().forEach(store::saveOrder);
            log.info("Refreshed demo customers and orders");
        };
    }

    public static void seed(StoreRepository store) {
        SeedData.products().forEach(store::saveProduct);
        SeedData.customers().forEach(store::saveCustomer);
        SeedData.orders().forEach(store::saveOrder);
    }
}

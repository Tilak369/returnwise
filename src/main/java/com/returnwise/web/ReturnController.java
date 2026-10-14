package com.returnwise.web;

import com.returnwise.agent.ReturnRequest;
import com.returnwise.ai.GeminiClient;
import com.returnwise.ai.GeminiClient.GeminiException;
import com.returnwise.config.ReturnWiseProperties;
import com.returnwise.config.StoreConfig;
import com.returnwise.model.Models.Order;
import com.returnwise.model.Models.ReturnCase;
import com.returnwise.service.ReturnService;
import com.returnwise.store.SeedData;
import com.returnwise.store.StoreRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Validated
public class ReturnController {

    private static final Logger log = LoggerFactory.getLogger(ReturnController.class);
    private static final int MAX_PHOTO_BASE64 = 4_000_000; // ~3 MB image

    private final ReturnService service;
    private final StoreRepository store;
    private final ReturnWiseProperties props;
    private final GeminiClient gemini;

    public ReturnController(ReturnService service, StoreRepository store, ReturnWiseProperties props,
                            GeminiClient gemini) {
        this.service = service;
        this.store = store;
        this.props = props;
        this.gemini = gemini;
    }

    public record CreateReturn(
            @NotBlank String orderId,
            @NotBlank String sku,
            @NotBlank @Size(max = 1000) String message,
            String photoBase64,
            String photoMimeType) {
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "engine", props.useGemini() ? "Gemini " + gemini.modelName() + " on Vertex AI" : "offline rules (local dev)",
                "store", props.useFirestore() ? "Firestore" : "in-memory",
                "returnWindowDays", props.returnWindowDays(),
                "demoOrders", SeedData.orders().stream().map(Order::orderId).toList());
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Order> order(@PathVariable String orderId) {
        return store.findOrder(orderId.trim().toUpperCase())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/returns")
    public ReturnCase create(@RequestBody @Validated CreateReturn body) {
        if (body.photoBase64() != null && body.photoBase64().length() > MAX_PHOTO_BASE64) {
            throw new IllegalArgumentException("Photo is too large; please use an image under 3 MB.");
        }
        String photo = body.photoBase64();
        if (photo != null && photo.startsWith("data:")) {
            photo = photo.substring(photo.indexOf(',') + 1);
        }
        return service.process(new ReturnRequest(body.orderId().trim().toUpperCase(), body.sku(), body.message(),
                photo, body.photoMimeType()));
    }

    @GetMapping("/returns")
    public List<ReturnCase> list(@RequestParam(defaultValue = "50") int limit) {
        return store.recentCases(Math.min(limit, 200));
    }

    @GetMapping("/returns/{caseId}")
    public ResponseEntity<ReturnCase> get(@PathVariable String caseId) {
        return store.findCase(caseId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Aggregates for the operations dashboard. */
    @GetMapping("/insights")
    public Map<String, Object> insights() {
        List<ReturnCase> cases = store.recentCases(200);
        Map<String, Long> byResolution = cases.stream()
                .collect(Collectors.groupingBy(c -> c.decision().resolution(), TreeMap::new, Collectors.counting()));
        Map<String, Long> byReason = cases.stream()
                .collect(Collectors.groupingBy(c -> String.valueOf(c.decision().reasonCategory()), TreeMap::new,
                        Collectors.counting()));
        long retained = cases.stream()
                .filter(c -> List.of("EXCHANGE", "SIZE_SWAP", "STORE_CREDIT").contains(c.decision().resolution()))
                .count();
        double retainedValue = cases.stream()
                .filter(c -> List.of("EXCHANGE", "SIZE_SWAP", "STORE_CREDIT").contains(c.decision().resolution()))
                .mapToDouble(c -> store.findProduct(c.sku()).map(p -> p.price()).orElse(0.0))
                .sum();
        double avgRisk = cases.stream().mapToInt(c -> c.decision().fraudRiskScore()).average().orElse(0);
        List<Map<String, String>> productInsights = cases.stream()
                .filter(c -> c.decision().productInsight() != null)
                .map(c -> Map.of("sku", c.sku(), "insight", c.decision().productInsight()))
                .distinct()
                .limit(10)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCases", cases.size());
        out.put("byResolution", byResolution);
        out.put("byReason", byReason);
        out.put("revenueRetainedCases", retained);
        out.put("revenueRetainedValue", Math.round(retainedValue * 100) / 100.0);
        out.put("averageRiskScore", Math.round(avgRisk));
        out.put("needsReview", cases.stream().filter(c -> "NEEDS_REVIEW".equals(c.status())).count());
        out.put("productInsights", productInsights);
        return out;
    }

    /** Restores demo catalogue stock and orders (does not delete past cases). */
    @PostMapping("/admin/reseed")
    public Map<String, String> reseed() {
        StoreConfig.seed(store);
        return Map.of("status", "reseeded");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", Objects.toString(e.getMessage(), "Bad request")));
    }

    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<Map<String, String>> aiError(GeminiException e) {
        log.error("Gemini failure", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "The AI agent is temporarily unavailable. Please try again."));
    }
}

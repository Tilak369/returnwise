package com.returnwise.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.returnwise.config.ReturnWiseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin client for Gemini on Vertex AI (generateContent). Authenticates with
 * Application Default Credentials, so on Cloud Run it uses the service's own
 * service account and no API key is ever stored in code or config.
 * <p>
 * Resilience for a demo that must stay up during judging:
 * <ul>
 *   <li>{@code returnwise.model} may be a comma-separated list; if a model is
 *       not found (e.g. retired), the client moves to the next one and stays there.</li>
 *   <li>429 / 5xx responses are retried with backoff.</li>
 * </ul>
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final int MAX_RETRIES = 3;

    private final ReturnWiseProperties props;
    private final ObjectMapper mapper;
    private final List<String> models;
    private final AtomicInteger activeModel = new AtomicInteger(0);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private GoogleCredentials credentials;

    public GeminiClient(ReturnWiseProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        String configured = props.model() == null || props.model().isBlank() ? "gemini-2.5-flash" : props.model();
        this.models = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public JsonNode generateContent(JsonNode request) {
        while (true) {
            int idx = activeModel.get();
            String model = models.get(idx);
            HttpResponse<String> response = send(model, tuneForModel(model, request));
            int code = response.statusCode();
            if (code < 300) {
                try {
                    return mapper.readTree(response.body());
                } catch (IOException e) {
                    throw new GeminiException("Unreadable Gemini response", e);
                }
            }
            if (code == 404 && idx + 1 < models.size()) {
                log.warn("Model {} not available (404); switching to {}", model, models.get(idx + 1));
                activeModel.compareAndSet(idx, idx + 1);
                continue;
            }
            throw new GeminiException("Gemini (" + model + ") returned HTTP " + code + ": " + response.body());
        }
    }

    public String modelName() {
        return models.get(activeModel.get());
    }

    String endpoint(String model) {
        String location = props.location() == null || props.location().isBlank() ? "global" : props.location();
        String host = "global".equals(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        return "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, props.projectId(), location, model);
    }

    /** Gemini 3 models are tuned for their default temperature; keep a low temperature only for 2.x. */
    private JsonNode tuneForModel(String model, JsonNode request) {
        if (!model.startsWith("gemini-3") || !request.has("generationConfig")) {
            return request;
        }
        ObjectNode copy = request.deepCopy();
        ((ObjectNode) copy.get("generationConfig")).remove("temperature");
        return copy;
    }

    private HttpResponse<String> send(String model, JsonNode request) {
        try {
            String body = mapper.writeValueAsString(request);
            for (int attempt = 0; ; attempt++) {
                HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint(model)))
                        .timeout(Duration.ofSeconds(90))
                        .header("Authorization", "Bearer " + accessToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                boolean retryable = code == 429 || code >= 500;
                if (!retryable || attempt >= MAX_RETRIES) {
                    return response;
                }
                long backoffMs = 800L * (1L << attempt);
                log.warn("Gemini HTTP {} (attempt {}); retrying in {} ms", code, attempt + 1, backoffMs);
                Thread.sleep(backoffMs);
            }
        } catch (IOException e) {
            throw new GeminiException("Gemini call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException("Gemini call interrupted", e);
        }
    }

    private synchronized String accessToken() throws IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(SCOPE);
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    public static class GeminiException extends RuntimeException {
        public GeminiException(String message) {
            super(message);
        }

        public GeminiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

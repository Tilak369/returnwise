package com.returnwise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All runtime settings, bound from application.yml / environment variables.
 *
 * @param projectId    Google Cloud project that hosts Firestore and Vertex AI
 * @param location     Vertex AI location for Gemini ("global" or a region such as "asia-southeast1")
 * @param model        Gemini model id, e.g. gemini-2.5-flash
 * @param store        "firestore" in the cloud, "memory" for local runs without GCP
 * @param aiMode       "gemini" to call Vertex AI, "offline" for a rule-based stand-in used only for local UI testing
 * @param returnWindowDays policy window for returns
 */
@ConfigurationProperties(prefix = "returnwise")
public record ReturnWiseProperties(
        String projectId,
        String location,
        String model,
        String store,
        String aiMode,
        int returnWindowDays
) {
    public boolean useFirestore() {
        return "firestore".equalsIgnoreCase(store);
    }

    public boolean useGemini() {
        return "gemini".equalsIgnoreCase(aiMode);
    }
}

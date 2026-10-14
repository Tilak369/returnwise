package com.returnwise.model;

import java.util.List;
import java.util.Map;

/**
 * Domain records. Stored in Firestore as plain maps (converted with Jackson),
 * which keeps the documents readable in the Firebase console.
 */
public final class Models {

    private Models() {
    }

    /** A product variant catalogue entry with stock per size. */
    public record Product(
            String sku,
            String name,
            String category,
            String color,
            double price,
            String description,
            Map<String, Integer> stockBySize,
            String fitNote
    ) {
    }

    public record OrderItem(
            String sku,
            String name,
            String size,
            int quantity,
            double price
    ) {
    }

    public record Order(
            String orderId,
            String customerId,
            String customerName,
            String placedAt,      // ISO date
            String deliveredAt,   // ISO date
            String channel,       // ONLINE / STORE
            List<OrderItem> items
    ) {
    }

    public record Customer(
            String customerId,
            String name,
            String tier,                // MEMBER / PLUS
            int ordersLast12Months,
            int returnsLast12Months,
            int returnsLast90Days,
            double lifetimeValue,
            List<String> notes
    ) {
    }

    /** One step in the agent's reasoning trail, shown on the ops dashboard. */
    public record AgentStep(
            String type,      // TOOL_CALL / TOOL_RESULT / DECISION / NOTE
            String name,
            String detail
    ) {
    }

    /** The structured decision the agent submits at the end of its run. */
    public record Decision(
            String resolution,        // REFUND / EXCHANGE / SIZE_SWAP / STORE_CREDIT / REJECT / MANUAL_REVIEW
            String exchangeSku,
            String exchangeSize,
            int fraudRiskScore,       // 0-100
            List<String> riskSignals,
            String photoAssessment,
            String reasonCategory,    // SIZE_FIT / DEFECT / NOT_AS_DESCRIBED / CHANGED_MIND / WRONG_ITEM / OTHER
            String internalRationale,
            String customerReply,
            String productInsight     // feedback for merchandising / product team
    ) {
    }

    public record ReturnCase(
            String caseId,
            String orderId,
            String customerId,
            String sku,
            String size,
            String customerMessage,
            boolean photoProvided,
            String status,            // AUTO_APPROVED / NEEDS_REVIEW / REJECTED
            Decision decision,
            List<AgentStep> trace,
            String createdAt,
            String engine             // which AI engine produced the decision
    ) {
    }
}

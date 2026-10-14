package com.returnwise.agent;

/**
 * A customer's return request as received from the web app.
 *
 * @param photoBase64 optional photo of the item (base64, no data: prefix)
 * @param photoMimeType e.g. image/jpeg
 */
public record ReturnRequest(
        String orderId,
        String sku,
        String customerMessage,
        String photoBase64,
        String photoMimeType
) {
    public boolean hasPhoto() {
        return photoBase64 != null && !photoBase64.isBlank();
    }
}

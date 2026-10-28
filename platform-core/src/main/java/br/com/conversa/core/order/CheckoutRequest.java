package br.com.conversa.core.order;

/**
 * A tool's request to end the turn by showing the customer an order summary with a confirmation
 * button. The platform stores the draft and renders the buttons; only the click confirms.
 *
 * @param summaryText the summary in the customer's language, built from {@code draft}
 */
public record CheckoutRequest(String summaryText, OrderDraft draft) {
}

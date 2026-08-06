package com.yayatechandinnovations.yayaagentic.channel.chat.receipts;

import java.util.List;
import java.util.Map;

/**
 * Starter templates seeded for a tenant with none ({@code
 * chat-experience-design.md} §7.7): operators begin from "tweak this", never
 * from an empty JSON editor. Layouts only — every domain word arrives at
 * runtime through {@code {{data}}} bindings, so the same starters serve any
 * vertical. The bound field names double as the reference data contract for
 * tools emitting {@code receipt} hints.
 */
final class ReceiptTemplateDefaults {

    private ReceiptTemplateDefaults() {}

    record Seed(String name, String kindLabel, int paperCols, String paperProfile,
                List<Map<String, Object>> elements) {}

    static List<Seed> all() {
        return List.of(saleReceipt(), numberedTicket(), confirmationSlip());
    }

    /** Classic purchase receipt: line items, total, barcode. */
    private static Seed saleReceipt() {
        return new Seed("Sale receipt", "General purchases", 32, "58mm", List.of(
                Map.of("type", "header", "logo", true,
                        "title", "{{org.name}}", "subtitle", "{{org.tagline}}"),
                Map.of("type", "status", "text", "{{statusStamp}}"),
                Map.of("type", "meta", "left", "{{issuedAt}}", "right", "N° {{serial}}"),
                Map.of("type", "divider", "style", "dashed"),
                Map.of("type", "table", "repeat", "lines", "cols", List.of(
                        Map.of("bind", "label", "align", "left"),
                        Map.of("bind", "qty", "align", "center"),
                        Map.of("bind", "amount", "align", "right", "format", "money"))),
                Map.of("type", "divider", "style", "dashed"),
                Map.of("type", "total", "label", "{{labels.total}}",
                        "value", "{{total | money}}"),
                Map.of("type", "barcode", "symbology", "code128", "value", "{{serial}}"),
                Map.of("type", "footer", "lines",
                        List.of("{{labels.thanks}}", "{{org.contact}}"))));
    }

    /** Numbered ticket with draw sections, a picks grid and a payment code —
     *  the counter-printed-ticket shape (design §7.4 reference anatomy). */
    private static Seed numberedTicket() {
        return new Seed("Numbered ticket", "Draws & games", 32, "58mm", List.of(
                Map.of("type", "header", "logo", true,
                        "title", "{{org.name}}", "subtitle", "{{branch.name}}"),
                Map.of("type", "status", "text", "{{statusStamp}}"),
                Map.of("type", "meta", "left", "{{issuedAt}}", "right", "N° {{serial}}"),
                Map.of("type", "divider", "style", "dashed"),
                Map.of("type", "section", "repeat", "sections",
                        "title", "{{title}}", "subtitle", "{{subtitle}}",
                        "elements", List.of(
                                Map.of("type", "table", "repeat", "rows", "cols", List.of(
                                        Map.of("bind", "numbers", "align", "left"),
                                        Map.of("bind", "game", "align", "center"),
                                        Map.of("bind", "amount", "align", "right",
                                                "format", "money"))))),
                Map.of("type", "cells", "repeat", "picks", "bind", "value",
                        "emphasis", true),
                Map.of("type", "divider", "style", "dashed"),
                Map.of("type", "total", "label", "{{labels.total}}",
                        "value", "{{total | money}}"),
                Map.of("type", "code", "label", "{{labels.paymentCode}}",
                        "value", "{{paymentCode | group:3}}"),
                Map.of("type", "kv", "label", "{{labels.customer}}",
                        "value", "{{customer.phone | maskTail:4}}"),
                Map.of("type", "qr", "value", "{{verifyUrl}}"),
                Map.of("type", "footer", "lines",
                        List.of("{{labels.checkTicket}}", "{{labels.thanks}}"))));
    }

    /** Minimal confirmation slip: a title, a few facts, a QR. */
    private static Seed confirmationSlip() {
        return new Seed("Confirmation slip", "Bookings & pickups", 32, "58mm", List.of(
                Map.of("type", "header", "logo", true, "title", "{{org.name}}"),
                Map.of("type", "title", "text", "{{docTitle}}", "align", "center"),
                Map.of("type", "meta", "left", "{{issuedAt}}", "right", "N° {{serial}}"),
                Map.of("type", "divider", "style", "dashed"),
                Map.of("type", "kv", "label", "{{labels.name}}", "value", "{{customer.name}}"),
                Map.of("type", "kv", "label", "{{labels.when}}", "value", "{{scheduledFor}}"),
                Map.of("type", "kv", "label", "{{labels.reference}}", "value", "{{reference}}"),
                Map.of("type", "spacer"),
                Map.of("type", "qr", "value", "{{verifyUrl}}"),
                Map.of("type", "footer", "lines", List.of("{{labels.instructions}}"))));
    }
}

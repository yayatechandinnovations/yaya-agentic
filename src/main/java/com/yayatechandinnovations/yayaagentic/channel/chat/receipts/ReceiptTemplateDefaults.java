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

    /**
     * Numbered ticket — a faithful replica of a real counter-printed ticket
     * (modeled on a shipped thermal-printer {@code detailed} layout:
     * header → meta → sections → totals → payment split → payment code →
     * customer → QR → hash → footer → printed-at). Field order, labels, the
     * {@code Count | Total} line, the two-digit payment-code grouping, the
     * loyalty lines and the return-if-not-yours disclaimer all mirror that
     * renderer. Every value is a {{data}} binding — no domain word in the
     * schema; unbound fields (payment split, points, hash on a plain sale)
     * simply don't print, exactly as the printer omits them.
     */
    private static Seed numberedTicket() {
        return new Seed("Numbered ticket", "Draws & games", 32, "58mm", List.of(
                // Header: org name (bold) then the station line — the printer's
                // orgHeaderMode=name + station value.
                Map.of("type", "header", "logo", true,
                        "title", "{{org.name}}", "subtitle", "{{station.name}}"),
                // Reprint / CANCELLED / VOIDED / PAID banner.
                Map.of("type", "status", "text", "{{statusStamp}}"),
                // Meta: "Date: <d> <t>" and "Ticket: <ticketNumber>-<reference>".
                Map.of("type", "kv", "label", "{{labels.date}}", "value", "{{issuedAt}}"),
                Map.of("type", "kv", "label", "{{labels.ticket}}", "value", "{{ticketRef}}"),
                Map.of("type", "divider", "style", "dashed"),
                // One section per draw group: title (+ inline close time as
                // subtitle → the "For draws:" header), then the play rows —
                // "<numbers/desc> ........ <amount>".
                Map.of("type", "section", "repeat", "sections",
                        "title", "{{title}}", "subtitle", "{{forDraws}}",
                        "elements", List.of(
                                Map.of("type", "table", "repeat", "rows", "cols", List.of(
                                        Map.of("bind", "play", "align", "left"),
                                        Map.of("bind", "amount", "align", "right",
                                                "format", "money"))))),
                Map.of("type", "divider", "style", "dashed"),
                // Totals: "Count: N   Total: $X" — the printer's single two-col line.
                Map.of("type", "meta",
                        "left", "{{labels.count}}: {{count}}",
                        "right", "{{labels.total}}: {{total | money}}"),
                // Payment split — only prints when wallet/points funded part of
                // the total (unbound → skipped, like the all-cash ticket).
                Map.of("type", "kv", "label", "{{labels.points}}",
                        "value", "{{payment.points | money}}"),
                Map.of("type", "kv", "label", "{{labels.balance}}",
                        "value", "{{payment.wallet | money}}"),
                Map.of("type", "kv", "label", "{{labels.cashDue}}",
                        "value", "{{payment.cashDue | money}}"),
                // Payment code: grouped two digits at a time — "48 29 13".
                Map.of("type", "code", "label", "{{labels.paymentCode}}",
                        "value", "{{paymentCode | group:2}}"),
                // Customer: name · masked phone, then loyalty lines + disclaimer.
                Map.of("type", "kv", "label", "{{labels.customer}}",
                        "value", "{{customer.name}} {{customer.phone | maskTail:4}}"),
                Map.of("type", "kv", "label", "{{labels.pointsEarned}}",
                        "value", "{{customer.pointsEarned}}"),
                Map.of("type", "qr", "value", "{{verifyUrl}}"),
                // Ticket hash (integrity stamp) — centered, prints when present.
                Map.of("type", "text", "text", "{{ticketHash}}", "align", "center"),
                // Courtesy footer + the return-if-not-yours disclaimer.
                Map.of("type", "footer", "lines", List.of(
                        "{{disclaimer}}", "{{labels.checkTicket}}", "{{labels.thanks}}")),
                // Security stamp at the tear line: "Printed: <d> <t>".
                Map.of("type", "text", "text", "{{labels.printed}}: {{printedAt}}",
                        "align", "center")));
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

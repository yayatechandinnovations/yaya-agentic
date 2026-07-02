package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The webhook closes the money loop: a PSP callback for a PENDING order flips it
 * to PAID. Sandbox mode (no dLocal credentials) accepts the unsigned callback so
 * this runs with no external dependency. See {@code ecommerce-chat-design.md} §11.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class DLocalWebhookIT {

    @Autowired WebTestClient client;
    @Autowired CommerceOrderRepository orders;

    @Test
    void paidWebhookFlipsThePendingOrder() {
        String orderRef = "ord_" + UUID.randomUUID().toString().substring(0, 8) + "_2598";
        orders.save(new CommerceOrderEntity(UUID.randomUUID(), UUID.randomUUID(), "default",
                "dlocal", orderRef, 2598, "USD", "PENDING"));

        client.post().uri("/v1/commerce/webhooks/dlocal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"order_id\":\"" + orderRef + "\",\"status\":\"PAID\"}")
                .exchange()
                .expectStatus().isOk();

        assertEquals("PAID", orders.findByOrderRef(orderRef).orElseThrow().getStatus());
    }
}

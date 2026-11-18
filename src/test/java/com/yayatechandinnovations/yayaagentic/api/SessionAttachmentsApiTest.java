package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Attachments on the sessions API (tracker contract C): upload by the owner,
 * the error codes, sending with {@code attachmentIds}, the real turn id on the
 * row, history with fresh links, the token-only media route, and the usage
 * fields. Shares its context with {@link AppSessionsApiTest}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, AppSessionsApiTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.llm.allowed-models=test-model-large,test-model-small",
        "yaya.agentic.sessions.messages-per-minute=6",
        "yaya.agentic.sessions.session-starts-per-minute=5",
        "yaya.agentic.sessions.extra-origins=https://shared-admin.example.com"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionAttachmentsApiTest {

    private static final String ISS = "panel-test-identity";
    private static final String SECRET = "one-install-signs-for-every-business-it-hosts-32+";
    private static final String STAFF = "TestIdentity/staff";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired TenantOrigins tenantOrigins;
    @Autowired ObjectMapper json;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired UsageMeter usage;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String tenant = "media-" + run;
    private final String org = "org-m-" + run;

    @BeforeAll
    void install() {
        TenantEntity t = new TenantEntity(tenant, "Media " + tenant);
        t.setHostBaseUrl("https://" + tenant + ".example.com");
        tenants.save(t);
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        TenantJwtSecretEntity trust = new TenantJwtSecretEntity(tenant, ISS, enc.ciphertext(), enc.iv(), "[]");
        trust.setPrincipalKind(STAFF);
        trust.setRequiredClaimsJson("{\"org\":\"" + org + "\"}");
        jwtSecrets.save(trust);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenant));
        tenantOrigins.evict();
        profiles.register(new Profile(new Ids.ProfileId("media_owner", 1), new Ids.TenantId(tenant), "Media owner",
                "Hola.", "You are the operations assistant.", List.of(), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), null, new Profile.Runtime(null, 4, true)));
        profiles.register(new Profile(new Ids.ProfileId("media_open", 1), new Ids.TenantId(tenant), "Media open",
                "Hola.", "You are the assistant.", List.of(), List.of(), null, "es", Map.of(), Map.of(), null, null));
    }

    @BeforeEach
    void reset() {
        llm.reset();
    }

    @Test
    void the_owner_uploads_a_photo_and_its_codes_come_back_with_a_signed_link() {
        String user = "u-" + UUID.randomUUID();
        String sid = start(token(user));

        Map<String, Object> created = upload(sid, token(user), "ticket.png",
                TestMedia.qrPng("4130552218-7781902345", 300), 201);

        assertThat(created.get("kind")).isEqualTo("IMAGE");
        assertThat(created.get("filename")).isEqualTo("ticket.png");
        assertThat(created.get("contentType")).isEqualTo("image/png");
        assertThat((String) created.get("id")).startsWith("chatatt_");
        assertThat((String) created.get("url")).startsWith("/v1/chat/media/");
        assertThat(created.get("extractedTextChars")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codes = (List<Map<String, Object>>) created.get("codes");
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0)).containsEntry("format", "QR_CODE").containsEntry("text", "4130552218-7781902345");
        assertThat(attachments.findById((String) created.get("id"))).hasValueSatisfying(row -> {
            assertThat(row.getPrincipalSubject()).isEqualTo(user);
            assertThat(row.getAccountId()).isNull();
            assertThat(row.getTurnId()).isNull();
            assertThat(row.getCodesJson()).contains("QR_CODE").contains("4130552218-7781902345");
        });

        // The link is the authorization: no header, 15 minutes.
        client.get().uri((String) created.get("url")).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png");
    }

    @Test
    void only_the_owner_uploads_and_a_playground_session_cannot() {
        String owner = "u-" + UUID.randomUUID();
        String sid = start(token(owner));
        upload(sid, token("u-" + UUID.randomUUID()), "p.png", TestMedia.png(40, 40), 404);
        Map<String, Object> noToken = upload(sid, null, "p.png", TestMedia.png(40, 40), 401);
        assertThat(noToken.get("code")).isEqualTo("auth_required");

        String playground = (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tenant", tenant, "profileId", "media_open"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
        Map<String, Object> refused = upload(playground, null, "p.png", TestMedia.png(40, 40), 403);
        assertThat(refused.get("code")).isEqualTo("owner_required");
    }

    @Test
    void the_error_codes_of_contract_c() {
        String user = "u-" + UUID.randomUUID();
        String sid = start(token(user));

        Map<String, Object> exe = upload(sid, token(user), "x.exe", new byte[]{'M', 'Z', 0, 0, 1, 2, 3}, 415);
        assertThat(exe.get("code")).isEqualTo("unsupported_type");
        assertThat(exe).doesNotContainKey("hint");

        Map<String, Object> heic = upload(sid, token(user), "IMG_1.heic", TestMedia.heic(), 415);
        assertThat(heic.get("code")).isEqualTo("unsupported_type");
        assertThat(heic.get("hint")).isEqualTo("Mándala como JPG o PNG");

        Map<String, Object> big = upload(sid, token(user), "big.png", TestMedia.noisyPng(2_200, 1_700), 413);
        assertThat(big.get("code")).isEqualTo("too_large");

        Map<String, Object> longNote = upload(sid, token(user), "note.wav", TestMedia.wav(130), 413);
        assertThat(longNote.get("code")).isEqualTo("too_large");
        assertThat(longNote.get("hint")).isEqualTo("La nota de voz es muy larga (máximo 2 minutos)");

        // No transcriber in this context: the audio is kept, the id returned, the transcript null.
        Map<String, Object> voice = upload(sid, token(user), "note.wav", TestMedia.wav(3), 422);
        assertThat(voice.get("code")).isEqualTo("transcription_failed");
        assertThat((String) voice.get("id")).startsWith("chatatt_");
        assertThat(voice.get("transcript")).isNull();
        assertThat(voice.get("durationSeconds")).isEqualTo(3);
        assertThat(attachments.findById((String) voice.get("id"))).isPresent();
    }

    @Test
    void too_many_uploads_on_one_session_answer_429() {
        String user = "u-" + UUID.randomUUID();
        String sid = start(token(user));
        byte[] png = TestMedia.png(16, 16);
        for (int i = 0; i < SessionAttachmentController.UPLOADS_PER_SESSION_PER_HOUR; i++) {
            upload(sid, token(user), "p" + i + ".png", png, 201);
        }
        Map<String, Object> refused = upload(sid, token(user), "p-last.png", png, 429);
        assertThat(refused.get("code")).isEqualTo("too_many_uploads");
    }

    @Test
    void a_message_with_attachments_reaches_the_model_marks_the_rows_with_the_turn_and_shows_in_history() {
        String user = "u-" + UUID.randomUUID();
        String tok = token(user);
        String sid = start(tok);
        String photo = (String) upload(sid, tok, "ticket.png", TestMedia.qrPng("4130552218-7781902345", 300), 201).get("id");
        String pdf = (String) upload(sid, tok, "factura.pdf", TestMedia.pdf("Factura 4411 codigo 481516"), 201).get("id");
        long imagesBefore = usage.usage(tenant, null).imagesIn();
        llm.say("Veo el ticket 4130552218.");

        // A cold engine path with vision input can take a few seconds the first time.
        String sse = client.mutate().responseTimeout(java.time.Duration.ofSeconds(60)).build()
                .post().uri("/v1/sessions/{id}/messages", sid)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tok)
                .bodyValue(Map.of("text", "¿qué ticket es este?", "attachmentIds", List.of(photo, pdf)))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(sse).contains("Veo el ticket");

        // What the model saw: the photo as vision input, the QR inside the data markers, the PDF text redacted.
        var request = llm.lastRequest();
        var last = request.history().get(request.history().size() - 1);
        assertThat(last).isInstanceOf(com.yayatechandinnovations.yayaagentic.llm.LlmClient.HistoryEntry.User.class);
        var userEntry = (com.yayatechandinnovations.yayaagentic.llm.LlmClient.HistoryEntry.User) last;
        assertThat(userEntry.images()).hasSize(1);
        assertThat(userEntry.content()).contains("¿qué ticket es este?")
                .contains("Código leído en la imagen: 4130552218-7781902345")
                .contains("Factura 4411 codigo ••••••")
                .doesNotContain("481516");

        // The rows belong to the turn — under the engine's turn id, not a placeholder.
        for (String id : List.of(photo, pdf)) {
            assertThat(attachments.findById(id)).hasValueSatisfying(row -> {
                assertThat(row.getTurnId()).isNotNull().isNotEqualTo("sent");
                assertThat(row.getReferencedAt()).isNotNull();
            });
        }
        assertThat(usage.usage(tenant, null).imagesIn()).isEqualTo(imagesBefore + 1);

        Map<String, Object> history = read(client.get().uri("/v1/sessions/{id}/history", sid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tok)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turns = (List<Map<String, Object>>) history.get("turns");
        Map<String, Object> userTurn = turns.stream().filter(t -> "user".equals(t.get("role"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) userTurn.get("attachments");
        assertThat(files).hasSize(2);
        assertThat(files.get(0)).containsEntry("id", photo).containsEntry("kind", "IMAGE")
                .containsEntry("filename", "ticket.png");
        assertThat((String) files.get(0).get("url")).startsWith("/v1/chat/media/");
        assertThat(files.get(0).get("codes")).isNotNull();
        assertThat(files.get(1)).containsEntry("id", pdf).containsEntry("kind", "DOCUMENT");

        // Single-use: the same ids cannot be sent twice.
        client.post().uri("/v1/sessions/{id}/messages", sid)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tok)
                .bodyValue(Map.of("text", "otra vez", "attachmentIds", List.of(photo)))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void more_than_four_attachments_on_one_message_is_refused() {
        String user = "u-" + UUID.randomUUID();
        String tok = token(user);
        String sid = start(tok);
        client.post().uri("/v1/sessions/{id}/messages", sid)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tok)
                .bodyValue(Map.of("text", "x", "attachmentIds", List.of("a", "b", "c", "d", "e")))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void usage_reports_voice_and_images() {
        String user = "u-" + UUID.randomUUID();
        usage.recordVoiceSeconds(tenant, "media_owner", 14);
        Map<String, Object> body = read(client.get().uri("/v1/sessions/usage?tenant={t}", tenant)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(((Number) body.get("voiceSecondsUsed")).longValue()).isGreaterThanOrEqualTo(14);
        assertThat(body.get("voiceSecondsLimit")).isEqualTo(3600);
        assertThat(body).containsKey("imagesIn").containsKey("monthlyMessages");
    }

    /* ------------------------------- helpers ------------------------------- */

    private String start(String token) {
        return (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", "media_owner", "channel", "admin-app"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
    }

    private Map<String, Object> upload(String sessionId, String token, String filename, byte[] bytes, int expected) {
        MultipartBodyBuilder mp = new MultipartBodyBuilder();
        mp.part("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        WebTestClient.RequestBodySpec req = client.post().uri("/v1/sessions/{id}/attachments", sessionId)
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        byte[] body = req.body(BodyInserters.fromMultipartData(mp.build()))
                .exchange().expectStatus().isEqualTo(expected)
                .expectBody().returnResult().getResponseBody();
        return body == null || body.length == 0 ? Map.of() : read(body);
    }

    private Map<String, Object> read(byte[] body) {
        try {
            return json.readValue(body, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + (body == null ? null : new String(body)), e);
        }
    }

    private String token(String subject) {
        try {
            Date now = new Date();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISS).subject(subject).claim("org", org).claim("username", "user-" + subject)
                    .issueTime(now).expirationTime(new Date(now.getTime() + 300_000L))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

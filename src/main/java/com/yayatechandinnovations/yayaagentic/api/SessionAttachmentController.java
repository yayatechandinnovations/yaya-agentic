package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code POST /v1/sessions/{id}/attachments} (multipart {@code file}, optional
 * {@code durationSeconds} for voice notes) — tracker contract C. Admission is
 * the session's owner check ({@link SessionController#admit}); the caps and
 * the gauntlet are the chat's ({@link ChatAttachmentService}); the per-subject
 * daily byte ceiling is keyed on {@code chat_attachments.principal_subject}.
 * Admission and the per-session rate limit run BEFORE the multipart body is
 * read, and a {@code Content-Length} over the read bound is refused outright:
 * nobody buffers 16 MB on this server without a valid owner token.
 *
 * <p>Answers: {@code 201} with the attachment; {@code 413 too_large},
 * {@code 415 unsupported_type} (with a {@code hint} for HEIC),
 * {@code 429 too_many_uploads} / {@code quota_exceeded} /
 * {@code voice_quota_exceeded}, {@code 422 transcription_failed} (audio kept,
 * {@code id} returned, {@code transcript} null). The file is served by
 * {@code GET /v1/chat/media/{token}} — the signed token is the authorization,
 * no header needed, 15 minutes.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionAttachmentController {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAttachmentController.class);
    static final int UPLOADS_PER_SESSION_PER_HOUR = 20;
    private static final Duration HOUR = Duration.ofHours(1);
    /** Hard multipart read bound: the largest per-kind cap, plus form overhead. */
    private static final int MAX_UPLOAD_READ_BYTES = 16 * 1024 * 1024;

    private final SessionController sessions;
    private final ChatRateLimiter rateLimiter;
    private final SessionAttachments attachments;

    public SessionAttachmentController(SessionController sessions,
                                       ChatRateLimiter rateLimiter,
                                       SessionAttachments attachments) {
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.attachments = attachments;
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> upload(@PathVariable("id") String id, ServerWebExchange exchange) {
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > MAX_UPLOAD_READ_BYTES) {
            return Mono.just(error(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "this file is too large", null));
        }
        return Mono.fromCallable(() -> admit(id, exchange))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(admission -> admission.refused().isPresent()
                        ? Mono.just(admission.refused().get())
                        : readAndStore(id, exchange, admission.caller()));
    }

    /** The owner check and the rate limit, with nothing of the body read yet. */
    private record Admission(SessionController.Caller caller, Optional<ResponseEntity<Object>> refused) {}

    private Admission admit(String id, ServerWebExchange exchange) {
        // The session's owner, on a session that may still take a turn.
        SessionController.Caller caller = sessions.admit(id, exchange, true);
        if (!caller.owned()) {
            throw new ChatApiException(HttpStatus.FORBIDDEN, "owner_required",
                    "attachments can only be sent on a session a signed-in user owns", 0);
        }
        ChatRateLimiter.Decision decision;
        try {
            decision = rateLimiter.hit("sess-att", id, UPLOADS_PER_SESSION_PER_HOUR, HOUR);
        } catch (RuntimeException e) {
            LOG.warn("attachment rate limiter unavailable ({}); allowing", e.getClass().getSimpleName());
            decision = null;
        }
        if (decision != null && !decision.allowed()) {
            return new Admission(caller, Optional.of(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(Math.max(1, decision.retryAfterSeconds())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(HttpStatus.TOO_MANY_REQUESTS, "too_many_uploads",
                            "too many uploads on this session; try again later", null))));
        }
        return new Admission(caller, Optional.empty());
    }

    private Mono<ResponseEntity<Object>> readAndStore(String id, ServerWebExchange exchange,
                                                      SessionController.Caller caller) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part filePart = parts.getFirst("file");
            if (!(filePart instanceof FilePart file)) {
                return Mono.just(error(HttpStatus.BAD_REQUEST, "missing_file", "send the file as the multipart part 'file'", null));
            }
            Integer durationHint = durationHint(parts.getFirst("durationSeconds"));
            return DataBufferUtils.join(file.content(), MAX_UPLOAD_READ_BYTES)
                    .map(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        DataBufferUtils.release(buf);
                        return bytes;
                    })
                    .publishOn(Schedulers.boundedElastic())
                    .map(bytes -> store(id, caller, file.filename(), bytes, durationHint))
                    .onErrorResume(DataBufferLimitException.class, e ->
                            Mono.just(error(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "this file is too large", null)));
        });
    }

    private ResponseEntity<Object> store(String id, SessionController.Caller caller, String filename, byte[] bytes,
                                         Integer durationHint) {
        Ids.TenantId tenant = caller.auth().tenant();
        String language = caller.profile() == null ? null : caller.profile().language();
        ChatAttachmentService.Uploader by = ChatAttachmentService.Uploader.subject(
                caller.principal().subject(), caller.row().getProfileId(), language, durationHint);
        ChatAttachmentService.Uploaded uploaded;
        try {
            uploaded = attachments.service().upload(tenant, id, by, filename, bytes);
        } catch (ChatAttachmentService.RejectedException e) {
            return rejected(e);
        }
        SessionDtos.AttachmentResponse response = attachments.response(tenant, uploaded.attachment());
        if (uploaded.transcriptionFailed()) {
            Map<String, Object> out = body(HttpStatus.UNPROCESSABLE_ENTITY, "transcription_failed",
                    "the voice note was kept but could not be transcribed", "No pude transcribir la nota");
            out.put("id", response.id());
            out.put("kind", response.kind());
            out.put("filename", response.filename());
            out.put("contentType", response.contentType());
            out.put("sizeBytes", response.sizeBytes());
            out.put("transcript", null);
            out.put("durationSeconds", response.durationSeconds());
            out.put("codes", response.codes());
            out.put("extractedTextChars", response.extractedTextChars());
            out.put("url", response.url());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON).body(out);
        }
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(response);
    }

    private static ResponseEntity<Object> rejected(ChatAttachmentService.RejectedException e) {
        HttpStatus status = switch (e.code()) {
            case "too_large" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "unsupported_type" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case "quota_exceeded", "voice_quota_exceeded" -> HttpStatus.TOO_MANY_REQUESTS;
            case "not_found" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, e.code(), e.getMessage(), e.hint());
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message, String hint) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(body(status, code, message, hint));
    }

    private static Map<String, Object> body(HttpStatus status, String code, String message, String hint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", status.name());
        out.put("message", message);
        out.put("code", code);
        if (hint != null) out.put("hint", hint);
        return out;
    }

    private static Integer durationHint(Part part) {
        if (!(part instanceof FormFieldPart field)) return null;
        try {
            int v = Integer.parseInt(field.value().trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

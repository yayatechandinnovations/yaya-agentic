package com.yayatechandinnovations.yayaagentic.outreach.media;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** §10 #2: signed, single-purpose, tamper-evident media tokens. */
class MediaTokenSignerTest {

    private final MediaTokenSigner signer = new MediaTokenSigner("test-key-0123456789abcdef");
    private static final Ids.TenantId ACME = new Ids.TenantId("acme");

    @Test
    void roundTripsTenantAndAsset() {
        String token = signer.mint(ACME, "media_1");
        Optional<MediaTokenSigner.Grant> g = signer.verify(token);
        assertThat(g).isPresent();
        assertThat(g.get().tenant()).isEqualTo(ACME);
        assertThat(g.get().assetId()).isEqualTo("media_1");
    }

    @Test
    void rejectsTamperedToken() {
        String token = signer.mint(ACME, "media_1");
        // Flip the last signature char.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');
        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsForeignKey() {
        String token = signer.mint(ACME, "media_1");
        MediaTokenSigner other = new MediaTokenSigner("a-different-key-9876543210");
        assertThat(other.verify(token)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(signer.verify("not-a-token")).isEmpty();
        assertThat(signer.verify(null)).isEmpty();
        assertThat(signer.verify("a.b")).isEmpty();
    }
}

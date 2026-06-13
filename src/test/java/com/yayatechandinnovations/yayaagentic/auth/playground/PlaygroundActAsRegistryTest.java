package com.yayatechandinnovations.yayaagentic.auth.playground;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundActAsRegistryTest {

    @Test
    void put_then_get_returns_the_spec() {
        PlaygroundActAsRegistry registry = new PlaygroundActAsRegistry();
        Ids.SessionId sid = new Ids.SessionId("s-1");
        ActAs spec = new ActAs.RawToken("Bearer", "tok");

        registry.put(sid, spec);

        assertThat(registry.get(sid)).containsSame(spec);
    }

    @Test
    void get_unknown_session_returns_empty() {
        PlaygroundActAsRegistry registry = new PlaygroundActAsRegistry();

        assertThat(registry.get(new Ids.SessionId("missing"))).isEmpty();
    }

    @Test
    void remove_clears_the_spec() {
        PlaygroundActAsRegistry registry = new PlaygroundActAsRegistry();
        Ids.SessionId sid = new Ids.SessionId("s-1");
        registry.put(sid, new ActAs.RawToken("Bearer", "tok"));

        registry.remove(sid);

        assertThat(registry.get(sid)).isEmpty();
    }

    @Test
    void null_spec_is_ignored() {
        PlaygroundActAsRegistry registry = new PlaygroundActAsRegistry();

        registry.put(new Ids.SessionId("s-1"), null);

        assertThat(registry.get(new Ids.SessionId("s-1"))).isEmpty();
    }
}

package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Centralised Observation factory for the WhatsApp channel paths. The span
 * names match {@code whatsapp-channel-design.md} §17's ASCII trace tree so
 * an operator searching for {@code channel.whatsapp.inbound} in their OTel
 * collector finds exactly what the design promised.
 *
 * <p>This is a thin façade over Micrometer's {@link Observation} API rather
 * than direct OTel calls because the project's
 * {@code micrometer-tracing-bridge-otel} dependency already wires the
 * bridge — spans created through {@link Observation} flow into the OTel
 * exporter automatically.
 */
@Component
public class ChannelTracing {

    private final ObservationRegistry registry;

    public ChannelTracing(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** Wrap a void block in an observation; exceptions become span error events. */
    public void observe(String name, Runnable block, String... tagKvPairs) {
        Observation obs = Observation.start(name, registry);
        for (int i = 0; i + 1 < tagKvPairs.length; i += 2) {
            obs.lowCardinalityKeyValue(tagKvPairs[i], tagKvPairs[i + 1]);
        }
        try (Observation.Scope ignored = obs.openScope()) {
            block.run();
        } catch (RuntimeException e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    public <T> T observeCall(String name, Supplier<T> block, String... tagKvPairs) {
        Observation obs = Observation.start(name, registry);
        for (int i = 0; i + 1 < tagKvPairs.length; i += 2) {
            obs.lowCardinalityKeyValue(tagKvPairs[i], tagKvPairs[i + 1]);
        }
        try (Observation.Scope ignored = obs.openScope()) {
            return block.get();
        } catch (RuntimeException e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }
}

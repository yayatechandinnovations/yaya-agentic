package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Session;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * How a tenant or profile binds to recorders. Resolved per session at
 * start by {@link RecorderRouter}.
 */
public sealed interface RecordingStrategy
        permits RecordingStrategy.Single,
                RecordingStrategy.FanOut,
                RecordingStrategy.Tiered,
                RecordingStrategy.Classified {

    record Single(ConversationRecorder primary) implements RecordingStrategy {}

    record FanOut(ConversationRecorder primary,
                  List<ConversationRecorder> sinks) implements RecordingStrategy {}

    record Tiered(ConversationRecorder hot,
                  ConversationRecorder cold,
                  Duration hotWindow) implements RecordingStrategy {}

    record Classified(Function<Session, String> classifier,
                      Map<String, ConversationRecorder> byTier,
                      ConversationRecorder fallback) implements RecordingStrategy {}
}

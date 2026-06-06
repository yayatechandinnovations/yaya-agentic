package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;

public interface IntentTracker {
    IntentFrame update(List<Turn> history, UserMessage incoming, IntentFrame current);
}

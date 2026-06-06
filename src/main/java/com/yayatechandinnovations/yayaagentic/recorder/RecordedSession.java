package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;

public record RecordedSession(Session session, List<Turn> turns) {}

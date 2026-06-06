package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Single abstraction over conversation storage. The engine talks to this
 * interface for both reads and writes; storage choice is an implementation
 * detail. See design §5.9.
 */
public interface ConversationRecorder {

    // ---- Write path -------------------------------------------------
    void onSessionStarted(Session session, RecorderContext ctx);
    void onTurnRecorded(Ids.SessionId id, Turn turn, RecorderContext ctx);
    void onSessionEnded(Ids.SessionId id, Operations.SessionEndContext ctx);

    // ---- Read path --------------------------------------------------
    Optional<RecordedSession> loadSession(Ids.SessionId id, LoadOptions opts);
    List<Turn>                loadTurns(Ids.SessionId id, TurnRange range);
    Page<SessionSummary>      searchSessions(SessionQuery query);

    // ---- Lifecycle / compliance -------------------------------------
    Operations.RedactionResult redact(Ids.SessionId id, Operations.RedactionRequest req);
    void                       deleteSession(Ids.SessionId id, Operations.DeletionRequest req);
    Stream<Operations.SessionExport> exportForPrincipal(Principal p, Operations.ExportRequest req);
    void                       archive(Ids.SessionId id, Operations.ArchivePolicy policy);

    // ---- Capability advertisement -----------------------------------
    RecorderCapabilities capabilities();
}

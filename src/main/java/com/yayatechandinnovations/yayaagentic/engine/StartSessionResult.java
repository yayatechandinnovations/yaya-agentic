package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Session;

import java.util.List;

public record StartSessionResult(
        Session session,
        Ids.ProfileId profile,
        String greeting,
        List<String> quickReplies
) {}

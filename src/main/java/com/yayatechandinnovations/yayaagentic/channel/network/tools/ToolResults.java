package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerError;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small shapers for network tool_result values. The {@code message} is the
 *  user-safe copy the model paraphrases; {@code error} is the machine code the
 *  client logs for audit (two-reason discipline, design §4.1). */
final class ToolResults {

    private ToolResults() {}

    static Map<String, Object> error(BrokerError error, String userSafeMessage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("error", error == null ? "UNAVAILABLE" : error.name());
        m.put("message", userSafeMessage);
        return m;
    }
}

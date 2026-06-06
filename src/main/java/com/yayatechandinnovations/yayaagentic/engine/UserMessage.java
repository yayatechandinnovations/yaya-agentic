package com.yayatechandinnovations.yayaagentic.engine;

import java.util.Map;

public record UserMessage(String text, Map<String, Object> attachments) {}

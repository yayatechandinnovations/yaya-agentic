/**
 * Façade over LLM providers. Lets us stub in CI and swap providers without
 * touching the engine. M0 ships a Stub (default) and an Anthropic adapter
 * over Spring AI's ChatModel. Tool-calling integration is M2; M0's LLM
 * only streams plain tokens.
 */
package com.yayatechandinnovations.yayaagentic.llm;

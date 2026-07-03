/**
 * Session Start Flows: deterministic, operator-configured tool orchestration
 * run at session start — before the LLM — to recognize / verify / register /
 * enrich, and to shape the opening message from the results. Steps dispatch
 * through the same {@code ToolExecutor} + Authorizer chain as any tool call.
 * See {@code docs/design/session-start-flows.md}.
 */
package com.yayatechandinnovations.yayaagentic.startflow;

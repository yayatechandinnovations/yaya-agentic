/**
 * Authentication and authorization SPIs. Authenticators turn raw credentials
 * into a {@code Principal}; Authorizers decide ALLOW/DENY for tool calls and
 * knowledge-source reads. Tools NEVER trust the LLM — every call passes
 * through this chain. See design §5.4, §5.5.
 */
package com.yayatechandinnovations.yayaagentic.auth;

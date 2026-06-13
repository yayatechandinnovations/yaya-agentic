/**
 * Playground "act-as" credential plumbing — design
 * {@code docs/design/playground-actas-auth-design.md}.
 *
 * <p>The Flutter playground runs as an authenticated <em>operator</em>, not
 * as an end-user. When a profile's HTTP tools require a runtime
 * {@code Principal}, this package lets the operator supply an end-user
 * credential in a typed, audit-safe shape that arrives at the runtime
 * looking identical to a production request — preserving the two-plane
 * principle in {@code operator-auth-design.md} §2 and the no-trust-by-default
 * posture in {@code yaya-agentic-design.md} §12.
 */
package com.yayatechandinnovations.yayaagentic.auth.playground;

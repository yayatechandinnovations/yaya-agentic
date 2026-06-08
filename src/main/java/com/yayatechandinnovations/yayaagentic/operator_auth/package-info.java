/**
 * Console-operator authentication — distinct trust plane from
 * {@code auth/}, which authenticates conversational callers.
 *
 * <p>See {@code docs/design/operator-auth-design.md} §2 for the two-plane
 * principle and §3 for the SPI shape. Implementations contribute to
 * {@code OperatorAuthenticatorChain}, which composes ordered candidates
 * (typed by Spring {@code @Order}) and returns the first that recognises
 * the inbound credentials.</p>
 */
package com.yayatechandinnovations.yayaagentic.operator_auth;

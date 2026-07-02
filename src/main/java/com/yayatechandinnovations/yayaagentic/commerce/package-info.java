/**
 * Conversational storefront primitives: product catalogs (a
 * {@code KnowledgeSource}-shaped thing), products, a session-scoped cart, and
 * the commerce block a {@code Profile} carries to become a store. The catalog
 * is browsed/searched via HTTP commerce tools, rendered as {@code UiHint}s, and
 * checked out through a licensed PSP — the framework never touches money. See
 * {@code docs/design/ecommerce-chat-design.md} and design §5.2/§5.3/§5.8.
 */
package com.yayatechandinnovations.yayaagentic.commerce;

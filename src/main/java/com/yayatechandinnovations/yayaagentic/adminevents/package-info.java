/**
 * Admin change feed: metadata-only notifications ("tool sales.checkout is now
 * v4") that let the admin console refetch what changed instead of polling.
 * Fed from Hibernate post-commit events, so every committed write — an admin
 * save or a background status change such as ingestion finishing — reaches
 * the console without each controller publishing by hand. Payloads never carry
 * resource content; the console refetches through the normal authorized list
 * endpoints. See docs/design/admin-console-ux-design.md §4 (A3).
 */
package com.yayatechandinnovations.yayaagentic.adminevents;

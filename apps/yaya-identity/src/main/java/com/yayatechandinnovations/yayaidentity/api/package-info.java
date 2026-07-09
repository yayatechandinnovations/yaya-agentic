/**
 * HTTP API surface (design §9). The PDP seam ({@code /pdp/*}), the app-permission
 * manifest ({@code /v1/identity/apps/*}), and policy admin
 * ({@code /v1/identity/admin/*}). All are token-protected by the resource-server
 * security chain — a valid service/operator JWT is required.
 */
package com.yayatechandinnovations.yayaidentity.api;

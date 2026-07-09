/**
 * Clients module (design §8 module 7, §5.6). Relying-party apps and service
 * accounts: registration, secrets (hashed), grants, redirect URIs, scopes, and
 * audience binding. A {@code DbRegisteredClientRepository} adapts these rows to
 * Spring Authorization Server, so client registration is DB-backed rather than
 * an in-memory seed. Service accounts (client-credentials) are the
 * {@code SERVICE_TOKEN} relying parties consume.
 */
package com.yayatechandinnovations.yayaidentity.clients;

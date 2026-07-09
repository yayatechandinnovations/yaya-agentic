/**
 * Payments (module design {@code modules/payments/}, parent §8.3): the tender
 * model (PSP + COD + manual + store credit), payment ledger, PSP providers,
 * merchant onboarding, webhooks. S2 ships only {@link
 * com.yayatechandinnovations.yayasales.payments.TenderKind} (regions reference
 * it); the engines land in S3.
 */
package com.yayatechandinnovations.yayasales.payments;

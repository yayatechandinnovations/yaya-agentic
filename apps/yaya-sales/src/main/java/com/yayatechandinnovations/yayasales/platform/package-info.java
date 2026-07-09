/**
 * The correctness spine (module design {@code modules/platform/}, parent
 * §5.4–§5.7): typed IDs, exact money, idempotency, transactional outbox,
 * optimistic-concurrency helpers, secret cipher, and the auth caller model.
 * No business logic — every domain module leans on this one.
 *
 * <p>OPEN module: shared infrastructure, its sub-packages are the published
 * API surface for all domain modules.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.yayatechandinnovations.yayasales.platform;

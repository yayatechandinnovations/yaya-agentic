/**
 * Uploaded media (design §9 — what {@code products.media_json} and
 * {@code catalogs.cover_url} URLs point at). Image bytes live in Postgres:
 * self-hosted by construction, no object store to operate. Uploads are
 * operator-authenticated and store-scoped; serving is public (product photos
 * are public by nature) behind unguessable random ids.
 */
package com.yayatechandinnovations.yayasales.media;

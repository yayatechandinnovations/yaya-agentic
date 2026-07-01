# Knowledge File Upload — Design

**Status:** Proposed (production-ready target — not a throwaway MVP)
**Owner:** (you)
**Anchors:** design `§5.8` (Knowledge Sources & Retrieval), CLAUDE.md non-negotiables #5 (untrusted data) and #7 (per-source access), `§12` (trust boundaries)
**Supersedes nothing.** Adds a `SourceLocation` variant, a managed blob store, an async ingestion pipeline, and page-aware extraction. The `Inline` loader is untouched.

> **Update (security):** the `LocalPath` variant + `LocalPathLoader` have been **removed**. Reading operator-supplied server filesystem paths is an arbitrary-file-read / path-traversal risk (a source could be pointed at host secrets). Documents now enter only via `Uploaded` (managed storage) or `Inline`. Legacy `LOCAL_PATH` rows rehydrate to a `410 Gone` with a "re-create as an upload" message.

---

## 1. Problem

Today a knowledge source is created by describing *where its documents already live* on the machine running the backend:

- `SourceLocation.Inline` — the operator pastes raw markdown/text into the request body (`KnowledgeAdminController.decodeLocation` → `case "INLINE"`).
- `SourceLocation.LocalPath` — the operator types a **server filesystem path** (`root`, `includeGlob`) that `LocalPathLoader` walks.

Both leak an implementation detail onto the user. `LocalPath` is the worst offender: the Flutter admin console (`knowledge_sources_screen.dart`) literally asks operators to type a `root` path and an `includeGlob`. A customer configuring a `retail-customer` profile from a browser has **no idea** what the filesystem of the container looks like, cannot put files there, and in a multi-replica / containerized deploy the path is meaningless or non-deterministic. `Inline` sidesteps the path but only accepts text they've already converted by hand — no PDFs, no `.docx`, no HTML.

**We want:** the operator selects files from their own machine, uploads them through the admin console, and the framework stores, extracts, chunks, embeds, and retrieves them — with the *physical* storage location being an internal, swappable concern the user never sees.

### Product framing

"Point the bot at these 12 PDFs" is the request every customer actually has; "SSH a folder onto the server and type its absolute path" is what we make them do instead. This is the single biggest friction point in standing up a real profile, and it's the line between the admin console being a *product* and being a developer harness. Because we're building for production, the feature has to carry the weight of production: durable large-file handling, background ingestion that survives restarts, real document formats, malware scanning, and precise citations — not a happy-path demo.

---

## 2. Guiding constraints

1. **Production-ready from day one.** No "text-only phase," no synchronous best-effort ingestion, no deferred security. Binary formats, async durable ingestion, and AV scanning are baseline scope.
2. **Self-hosted, zero paid services** wherever a local option exists (explicit directive). Every new dependency this design introduces is OSS and runs in-cluster:

   | Need | Chosen (self-hosted, free) | Rejected (paid / cloud) |
   |---|---|---|
   | Object storage | **MinIO** (S3-compatible, runs in Docker) — or Postgres `bytea` for small deployments | AWS S3 / GCS |
   | Document text extraction | **Apache Tika** (embedded Java library — *no server, no network*) | Cloud document-AI / parsing APIs |
   | Malware scanning | **ClamAV** (`clamd` daemon, in-cluster) | Cloud AV APIs |
   | Job queue | **Postgres-backed queue** (`FOR UPDATE SKIP LOCKED`) — infra we already run | SQS / cloud queues |

   > Out of scope but noted: the *existing* RAG pipeline embeds via OpenAI `text-embedding-3-small` (`IngestionPolicy` default) — the one paid dependency in the wider system. This design does not touch embeddings, but per the same directive we should track a follow-up to move to a self-hosted embedding model (e.g. a local OpenAI-compatible server or an in-process ONNX model). The `BlobStore`/extraction work here is independent of that choice.

---

## 3. Goals / Non-goals

**Goals**
- Operators upload files from their browser; no server paths, ever.
- Physical storage is abstracted behind a `BlobStore` SPI — swap MinIO ↔ Postgres `bytea` with **zero changes** to loaders or the admin surface.
- Real document formats (PDF, DOCX, PPTX, XLSX, HTML, MD, TXT) via Apache Tika, with **page-level provenance** flowing into citations.
- **Asynchronous, durable, restart-safe ingestion** with retries, backoff, and observable per-source status.
- Honors every trust-boundary rule: tenant scoping, per-chunk sanitization, MIME allow-listing, size/quota caps, malware scanning.

**Non-goals**
- Not replacing `Inline` — it remains for tests, seeds, and quick text/markdown snippets. (`LocalPath` has been removed outright, see the security note above.)
- Not a document-management UI (folders, tags, rich preview) — upload → attach → index only.
- Not changing the embedding model (tracked separately, see §2).

---

## 4. Design overview

```
                       ┌─────────────────────────────────────────────┐
  Browser (Flutter)    │  POST /knowledge-sources/uploads (multipart) │ 1. upload
  file_picker  ───────▶│  → ClamAV scan → sniff type → BlobStore.store│
                       └───────────────┬─────────────────────────────┘
                                       │ returns blobId + metadata
                                       ▼
     locationKind="UPLOADED"    ┌──────────────────────────────┐
     location.files=[{blobId}]  │  BlobStore (SPI)              │  MinioBlobStore  (default)
  2. attach to KnowledgeSource ─│  store / open / stat / delete │  DbBlobStore     (bytea)
                                └───────────────┬──────────────┘
                                       │ auto-enqueues
                                       ▼
                       ┌──────────────────────────────┐
                       │  knowledge_ingestion_jobs      │  3. async, durable, retried
                       │  (Postgres queue, SKIP LOCKED) │     worker pool on boundedElastic
                       └───────────────┬──────────────┘
                                       ▼
                       ┌──────────────────────────────┐
                       │  UploadedFilesLoader           │  supports(SourceLocation.Uploaded)
                       │  BlobStore.open → bytes        │
                       │  TikaTextExtractor → pages     │  ← page-aware extraction
                       └───────────────┬──────────────┘
                                       ▼
        existing:  Chunker (page-tagged) → Sanitizer → EmbeddingService → pgvector
                                       ▼
                       RetrievedChunk.metadata carries {filename, page} → citations
```

Only one new sealed variant (`SourceLocation.Uploaded`). Everything from the chunker onward is the existing pipeline; the new work is upload, storage, async orchestration, extraction, and threading page provenance through.

---

## 5. New `SourceLocation` variant

> ⚠️ Adding a sealed-type variant is a CLAUDE.md "re-read the design" action. This document *is* that re-read. Audit of every `switch`/`instanceof` over `SourceLocation`: `KnowledgeAdminController.decodeLocation` (needs a new `case`), `IngestionOrchestrator` (selects by `supports()` — safe), the two existing loaders (`instanceof` guards — safe). Only `decodeLocation` changes.

```java
// SourceLocation.java — add to permits clause and as a record
record Uploaded(List<UploadedFile> files) implements SourceLocation {
    /**
     * A reference to bytes held by the BlobStore — NOT the bytes themselves.
     * location_json stores only these references, so the source row stays small
     * and storage can move MinIO↔Postgres without touching the source record.
     */
    record UploadedFile(
            String blobId,       // opaque, tenant-scoped handle
            String filename,     // original name — used as document title + citation label
            String contentType,  // sniffed server-side; client value is advisory only
            long   sizeBytes,
            String sha256        // integrity + within-tenant dedup + GC ref-counting
    ) {}
}
```

Why references, not bytes: `location_json` is a `jsonb` column, and every `PUT` clones the location into a new version. Inlining bytes would bloat every version. References keep versions cheap and let blobs be immutable and shared across versions.

Rehydration adds one arm to `decodeLocation`:

```java
case "UPLOADED" -> {
    List<SourceLocation.Uploaded.UploadedFile> files = new ArrayList<>();
    if (raw.get("files") instanceof List<?> list)
        for (Object f : list) if (f instanceof Map<?,?> m) files.add(
            new SourceLocation.Uploaded.UploadedFile(
                String.valueOf(m.get("blobId")), String.valueOf(m.get("filename")),
                String.valueOf(m.get("contentType")),
                ((Number) m.getOrDefault("sizeBytes", 0)).longValue(),
                String.valueOf(m.get("sha256"))));
    yield new SourceLocation.Uploaded(files);
}
```

---

## 6. `BlobStore` SPI — the "no physical location" abstraction

The user never names a location because a location is never *asked for*. No code above this SPI knows where bytes live.

```java
package com.yayatechandinnovations.yayaagentic.knowledge.blob;

public interface BlobStore {
    StoredBlob store(Ids.TenantId tenant, String filename, String contentType, InputStream data, long sizeBytes);
    Optional<InputStream> open(Ids.TenantId tenant, String blobId);   // streamed, not fully buffered
    Optional<BlobMetadata> stat(Ids.TenantId tenant, String blobId);
    void delete(Ids.TenantId tenant, String blobId);

    record StoredBlob(String blobId, String sha256, long sizeBytes, String contentType) {}
    record BlobMetadata(String filename, String contentType, long sizeBytes, String sha256) {}
}
```

`store`/`open` are **stream-oriented** (not `byte[]`) so large PDFs never sit fully in heap — a production requirement the logo path (`ChatLogoEntity.data` as `byte[]`) doesn't have to meet.

### Two implementations, both self-hosted

- **`MinioBlobStore` (default, recommended for prod).** MinIO is an S3-compatible object store that runs as a container in the same cluster — no cloud account, no bill. `blobId` is the object key (`{tenantId}/{ulid}`); `store` does a streaming `putObject`; `open` returns the object stream. Bucket-level SSE covers at-rest encryption. Scales past what a relational row can hold and keeps large binaries out of Postgres.
- **`DbBlobStore` (small deployments / single-node).** Postgres `bytea`, same pattern as `ChatLogoService`. Simple, one fewer moving part. For multi-hundred-MB corpora prefer MinIO (bytea reads buffer the whole value); this impl streams via chunked reads where possible.

Selected by config — a bean swap, nothing above it changes:

```yaml
yaya.agentic.knowledge.blob-store: minio        # minio | db
yaya.agentic.knowledge.minio:
  endpoint: http://minio:9000
  bucket: yaya-knowledge
```

This is the literal answer to "customers don't know the physical location": **neither does any loader, controller, or DTO.**

### `knowledge_blobs` table (next free migration — verify `Vnn` at build; V50 is current)

```sql
CREATE TABLE knowledge_blobs (
    tenant_id     VARCHAR(64)  NOT NULL,
    blob_id       VARCHAR(64)  NOT NULL,
    filename      TEXT         NOT NULL,
    content_type  VARCHAR(128) NOT NULL,        -- sniffed, authoritative
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    data          BYTEA,                          -- populated only when blob-store=db
    storage_ref   TEXT,                           -- object key when blob-store=minio
    scan_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING|CLEAN|INFECTED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, blob_id)
);
CREATE INDEX ix_knowledge_blobs_sha ON knowledge_blobs (tenant_id, sha256);  -- dedup lookup
```

---

## 7. Text extraction — Apache Tika, page-aware (production baseline)

`RawDocument.text` is a `String`, so binary → text happens inside the loader. Because this is production, we ship **all real formats immediately** via **Apache Tika**, an embedded library (in-process, no server, no paid API):

```java
public interface TextExtractor {
    boolean supports(String contentType);
    Extracted extract(InputStream in, String filename, String contentType);

    /** Page-aware: a document is a sequence of pages, each with its own text.
     *  Plain text/markdown collapse to a single page (pageNumber=1). */
    record Extracted(List<Page> pages, Map<String,Object> metadata) {}
    record Page(int pageNumber, String text) {}
}
```

- **`TikaTextExtractor`** — PDF, DOCX, PPTX, XLSX, HTML, RTF, ODF. Tika's PDF handler emits page boundaries (`<div class="page">` in its XHTML output); we split on those into `Page` records. For paginated office formats we map to slides/sheets; for flow formats (HTML, MD) there's one logical page.
- **Content type is sniffed, never trusted.** Tika's `Detector` runs on the actual bytes; the persisted `content_type` is authoritative. A `.txt` that is really a PDF is treated as a PDF; a renamed executable is rejected by the allow-list (§9).

Registered as `List<TextExtractor>` (first `supports()` wins), mirroring the loader registry.

### `UploadedFilesLoader`

```java
@Component
class UploadedFilesLoader implements KnowledgeLoader {
    public boolean supports(SourceLocation l) { return l instanceof SourceLocation.Uploaded; }

    public Stream<RawDocument> load(SourceLocation l, IngestionContext ctx) {
        var up = (SourceLocation.Uploaded) l;
        Ids.TenantId tenant = tenantFrom(ctx);                        // via ctx.attributes, §9
        return up.files().stream().map(f -> {
            InputStream in = blobStore.open(tenant, f.blobId()).orElseThrow(...);
            var ex = extractorFor(f.contentType()).extract(in, f.filename(), f.contentType());
            // Join pages into the document text, but keep a char-offset→page map so the
            // chunker can tag each chunk with the page(s) it came from (see §8).
            return toPageTaggedRawDocument(f, ex);
        });
    }
}
```

Downstream is unchanged: `IngestionOrchestrator` computes `sha256(text)` for idempotency, chunks, **sanitizes per chunk** (prompt-injection defense intact — non-negotiable #5), embeds, upserts.

---

## 8. Page-level citations (end-to-end)

Requirement: a retrieved chunk must cite the page it came from ("returns.pdf, p.4"). Path:

1. **Extractor** emits `List<Page>` (§7).
2. **Loader** concatenates page texts into `RawDocument.text` and records a `pageBreaks` offset map in `RawDocument.metadata` (`[{page:1,start:0},{page:2,start:1840},…]`). No SPI signature change — `RawDocument` already carries a `Map<String,Object> metadata`.
3. **Chunker** already produces chunks with char ranges; it stamps each chunk's metadata with `page` (or `pageStart`/`pageEnd` when a chunk straddles a break) by looking up its offset in `pageBreaks`. `Chunker.Chunk` already has a `metadata` map — this is additive.
4. **Persistence** — `IngestionOrchestrator` already writes `metadata_json` per chunk (`chunkRepo.insertChunk(..., writeJson(meta))`). `page` rides along; **no schema change to `knowledge_chunks`.**
5. **Retrieval** — `RetrievedChunk.metadata` surfaces `page`; `SearchKnowledgeToolHandler` includes it in each hit so the model's citation reads `filename p.N`. The `Citation` `TurnEvent` (design `§6`) carries it to the UI.

This is why page-level was worth choosing: it costs a metadata field threaded through layers that *already pass metadata*, and it materially improves answer trust ("per our returns policy, p.4…").

---

## 9. Asynchronous, durable ingestion

The current `reindex` is synchronous (`ingestion.ingest(source)` inline in the controller) — acceptable for M2.5-A seed corpora, **wrong for production**: a 200-page PDF blocks the request, a restart mid-ingest loses progress, and there's no retry. Uploading attaches files and **auto-enqueues** a durable job (resolves decision #2).

### Postgres-backed job queue (no new infra, no paid queue)

```sql
CREATE TABLE knowledge_ingestion_jobs (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    source_id    VARCHAR(128) NOT NULL,
    source_version INT        NOT NULL,
    state        VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',  -- QUEUED|RUNNING|DONE|FAILED
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL DEFAULT 5,
    last_error   TEXT,
    run_after    TIMESTAMPTZ  NOT NULL DEFAULT now(),      -- backoff gate
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

A worker pool claims jobs with the standard non-blocking pattern:

```sql
SELECT * FROM knowledge_ingestion_jobs
 WHERE state='QUEUED' AND run_after <= now()
 ORDER BY created_at
 FOR UPDATE SKIP LOCKED LIMIT 1;
```

`FOR UPDATE SKIP LOCKED` makes this **safe across replicas** with no coordinator — each pod claims disjoint jobs. Failures increment `attempts`, set `run_after = now() + backoff(attempts)` (exponential), and re-queue until `max_attempts`, then `FAILED` with `last_error`. Workers run the existing `IngestionOrchestrator.ingest(...)` on `Schedulers.boundedElastic()` — consistent with the reactive/Netty rule the recent "run blocking front-door work off the Netty event loop" commit established (`PublicChatController` already does `.subscribeOn(Schedulers.boundedElastic())`).

**Status is observable** via the existing `GET /knowledge-sources/{id}/status` (already returns `status`/`lastIndexedAt`/counts/`lastError`), extended with the active job's `state`/`attempts`. The source's `status` column (`UNINDEXED|INDEXING|READY|ERROR`) is driven by the worker, so the Flutter status badge just works.

---

## 10. API surface

Standardized on **multipart** (resolves decision #1) — base64 inflates ~33% and buffers whole files; wrong for document-sized payloads.

```
POST /v1/admin/knowledge-sources/uploads?tenant={t}
  Content-Type: multipart/form-data   (files=<binary>, repeatable)
  Role: MEMBER   → workspaceAccess.require(...,MEMBER); tenantGuard.requireWritable(...)
  Pipeline: stream part → ClamAV scan → Tika sniff → allow-list/size check → BlobStore.store
  200 → { files: [ { blobId, filename, contentType, sizeBytes, sha256 } ] }
  (dedup: identical sha256 within tenant returns the existing blobId — decision #3)

POST   /v1/admin/knowledge-sources/{id}/files?tenant={t}     upload + attach to latest
                                                             version + auto-enqueue ingest
DELETE /v1/admin/knowledge-sources/uploads/{blobId}?tenant={t}   remove an unreferenced blob
```

Reactive constraint: multipart parts stream to the `BlobStore` on `boundedElastic`, never buffered on the event loop.

Attaching still uses the **existing** create/update endpoints (they already accept opaque `locationKind` + `location`), so only `locationKind:"UPLOADED"` + a `files` array is new. Create/attach auto-enqueues an ingestion job (§9).

---

## 11. Security, quotas, tenancy

| Concern | Control |
|---|---|
| **Malware** | Every upload is streamed through **ClamAV** (`clamd`, self-hosted) *before* `BlobStore.store`. `INFECTED` → `422`, nothing persisted. `scan_status` recorded on the blob; ingestion refuses non-`CLEAN` blobs (defense in depth). |
| Tenant isolation | `blob_id` scoped by `tenant_id` (PK); every `open/stat/delete` takes `TenantId`; upload gated by `workspaceAccess.require(...,MEMBER)` + `tenantGuard.requireWritable`. No cross-tenant read path. |
| Content-type spoofing | Tika sniffs bytes; persisted type is authoritative; client type advisory. |
| MIME allow-list | Reject anything outside `text/*`, `application/pdf`, OOXML/ODF office types. No executables, archives, or SVG (XSS). |
| Size + quota | Per-file cap (configurable, default **25 MiB**), per-request cap (default **100 MiB**), **per-tenant corpus quota** (configurable, default **2 GiB** — decision #4) enforced pre-store → `413`. |
| Prompt injection | `ContentSanitizer` runs **per chunk** unchanged; extracted text is data, never instructions (non-negotiable #5). |
| Per-source access | `KnowledgeSource.access` (`PermissionRequirement`) unchanged; retrieval runs the `Authorizer` chain per source and silently drops ineligible ones (non-negotiable #7). Upload adds no bypass. |
| Tenant context to loader | `IngestionOrchestrator` puts `tenantId`/`sourceId`/`version` into the existing `IngestionContext.attributes`; `UploadedFilesLoader` reads them. No SPI signature change. |
| At-rest | MinIO bucket SSE or Postgres storage encryption; never a user-visible path. |

### Blob lifecycle & GC
Blobs are immutable and shared across source versions. Dedup is **within-tenant by `sha256`** (decision #3), and GC is **ref-counted**: a blob is deletable only when no live source version references its `blobId`. A scheduled sweep removes unreferenced blobs older than a grace window (default 24h), covering both never-attached uploads and blobs dropped by a new version.

---

## 12. Flutter admin console

`knowledge_sources_screen.dart` offers `UPLOADED | INLINE` (the former `LOCAL_PATH` segment and its `root`/`includeGlob` fields were removed). Changes:

- **`UPLOADED`** is the **default**; `INLINE` remains for quick text/markdown.
- `UPLOADED` renders a **drop-zone + file picker** (`file_picker`): pick one/many files → `POST …/uploads` (multipart) with per-file progress → uploaded files shown as chips (`filename · size · scan✓`), each removable. Chips are the `location.files` array.
- **Save** posts the source with `locationKind:"UPLOADED"`; ingestion is now async, so the screen shows a live **INDEXING → READY/ERROR** badge (polling `/status`) and, on completion, the existing `+X docs, +Y chunks` summary. Retrieval hits render `filename p.N` citations.
- `admin_api.dart` gains `uploadKnowledgeFiles(...)`, `deleteKnowledgeBlob(...)`; `knowledge_source.dart` gains an `UploadedFile` model. Existing create/reindex calls unchanged.

The operator never sees or types a path — they drag files in and hit save.

---

## 13. Resolved decisions

| # | Question | Decision |
|---|---|---|
| 1 | Upload transport | **Multipart**, standardized across the admin surface. |
| 2 | Auto-reindex on upload | **Yes** — attaching auto-enqueues a **durable async** ingestion job (§9), not a synchronous call. |
| 3 | Blob dedup | **Yes** — within-tenant dedup by `sha256`, with **ref-counted GC** across versions. |
| 4 | Per-tenant quota | **Yes** — ship a configurable per-tenant corpus cap (default 2 GiB) + per-file (25 MiB) and per-request (100 MiB) caps. |
| 5 | Citation granularity | **Page-level** — page provenance threaded extractor → chunk metadata → `RetrievedChunk` → `Citation` (§8). |
| — | Cost posture | Self-hosted OSS only: **MinIO, Apache Tika, ClamAV, Postgres queue**. No paid/cloud services (§2). Follow-up: move embeddings off OpenAI to a local model (out of scope here). |

---

## 14. Rollout (all production-grade; ordered by dependency, not by "MVP vs. later")

1. **Storage & upload — ✅ landed.** `BlobStore` SPI + `DbBlobStore` (Postgres bytea) + `knowledge_blobs` migration (V51); multipart upload endpoint (off the event loop) with Tika content sniff + allow-list/size/quota + within-tenant sha256 dedup; `MalwareScanner` seam with `NoOpMalwareScanner` default. *`MinioBlobStore` and `ClamAvMalwareScanner` are config-gated seams, not yet implemented.*
2. **Extraction & loader — ✅ landed.** `TextExtractor` + `TikaTextExtractor` (page-aware SAX split) + `ContentTypeSniffer`; `UploadedFilesLoader` (one `RawDocument` per page); `SourceLocation.Uploaded` + `decodeLocation` arm; page provenance threaded loader → chunk metadata → `RetrievedChunk` → `search_knowledge` hit.
3. **Async ingestion — ✅ landed.** `knowledge_ingestion_jobs` (V52) + `SKIP LOCKED` claim + `IngestionWorker` on a dedicated executor + exponential-backoff retries; uploaded sources auto-enqueue on create/update; `/status` surfaces job state/attempts/error. Decode logic extracted into a shared `KnowledgeSourceRehydrator` so the worker and admin decode identically. *(Synchronous `/reindex` retained for inline/local sources.)*
4. **Admin console — ✅ landed.** `UPLOADED` is the default location kind; upload zone + native multi-file picker (`web_file_picker.dart`) → chips with name/size/remove; auto background-index on save with a live status badge (list polling through INDEXING/QUEUED/RUNNING); clone/edit rehydrates the uploaded-file list. `admin_api` gains `uploadKnowledgeFiles`/`deleteKnowledgeBlob`/`knowledgeSourceStatus`; `UploadedFileRef` model added.
5. **GC & ops — ✅ landed.** Ref-counted orphan sweep (`BlobGcSweeper`, `@Scheduled`, `NOT EXISTS` over `location_json->files`); `MinioBlobStore` + `MinioConfig` (bytes in MinIO, metadata/dedup in `knowledge_blobs`); `ClamAvMalwareScanner` (INSTREAM, fail-closed); Micrometer metrics (`knowledge_uploads_total`, `knowledge_upload_bytes_total`, `knowledge_ingest_duration_seconds`, `knowledge_ingest_jobs_total`).

**All five rollout increments are implemented.** The one remaining item is cross-cutting and tracked separately under the same self-hosted cost directive: replacing the OpenAI embedding model with a self-hosted one.

### What shipped in increments 1–5

| Area | Files |
|---|---|
| Sealed variant | `knowledge/SourceLocation.java` (`Uploaded`) |
| Storage | `knowledge/blob/{BlobStore,DbBlobStore,MinioBlobStore,MinioConfig,MinioProperties,KnowledgeUploadProperties,KnowledgeUploadService}.java`; `persistence/KnowledgeBlob{Entity,Repository}.java`; `db/migration/V51__knowledge_blobs.sql` |
| Malware scan | `knowledge/blob/{MalwareScanner,NoOpMalwareScanner,ClamAvMalwareScanner,ClamAvProperties}.java` |
| Extraction | `knowledge/extract/{TextExtractor,TikaTextExtractor,ContentTypeSniffer}.java` |
| Loader / provenance | `knowledge/loaders/UploadedFilesLoader.java`; `IngestionOrchestrator` (tenant in context + page propagation + latency timer); `SearchKnowledgeToolHandler` (page in hit) |
| Async ingestion | `db/migration/V52__knowledge_ingestion_jobs.sql`; `persistence/KnowledgeIngestionJob{Entity,Repository}.java`; `knowledge/ingest/{KnowledgeSourceRehydrator,IngestionJobService,IngestionWorker,IngestionProperties}.java`; `knowledgeIngestExecutor` bean |
| GC & metrics | `knowledge/blob/{BlobGcSweeper,BlobGcProperties}.java`; `knowledge/KnowledgeMetrics.java` |
| API | `KnowledgeAdminController` (`POST /uploads`, `DELETE /uploads/{blobId}`, `UPLOADED` decode, auto-enqueue on create/update, `/status` job fields); `KnowledgeAdminDtos` (upload DTOs) |
| Config | `application.yml` (multipart limits + `yaya.agentic.knowledge.*` incl. `.ingestion.*` / `.gc.*` / `.minio.*` / `.clamav.*`); `CoreConfiguration` (props + executors) |
| Flutter console | `features/admin/knowledge_sources/{knowledge_sources_screen.dart,web_file_picker.dart}`; `models/admin/knowledge_source.dart` (`UploadedFileRef`); `api/admin_api.dart` (upload/delete/status) |
| Tests | `UploadedFilesLoaderTest`, `KnowledgeUploadPropertiesTest`, `IngestionPropertiesTest` (pure unit, no Docker) |

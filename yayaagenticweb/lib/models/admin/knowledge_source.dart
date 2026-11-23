/// Plain admin DTOs for knowledge sources. No freezed — the shape is
/// dominated by free-form Maps so generated boilerplate would add cost
/// without saving keystrokes.
class KnowledgeSourceResponse {
  KnowledgeSourceResponse({
    required this.tenant,
    required this.id,
    required this.version,
    required this.name,
    required this.locationKind,
    this.location = const {},
    this.ingestion = const {},
    this.retrieval = const {},
    this.access = const {},
    this.status = 'UNINDEXED',
    this.lastIndexedAt,
    this.docCount = 0,
    this.chunkCount = 0,
    this.lastError,
    this.createdAt,
    this.job,
  });

  final String tenant;
  final String id;
  final int version;
  final String name;
  final String locationKind;
  final Map<String, dynamic> location;
  final Map<String, dynamic> ingestion;
  final Map<String, dynamic> retrieval;
  final Map<String, dynamic> access;
  final String status;
  final String? lastIndexedAt;
  final int docCount;
  final int chunkCount;
  final String? lastError;
  final String? createdAt;

  /// The latest async ingestion job, when fetched from `/status` (the list
  /// endpoint doesn't carry it).
  final KnowledgeIngestionJob? job;

  KnowledgeSourceResponse withJob(KnowledgeIngestionJob? job) => KnowledgeSourceResponse(
        tenant: tenant,
        id: id,
        version: version,
        name: name,
        locationKind: locationKind,
        location: location,
        ingestion: ingestion,
        retrieval: retrieval,
        access: access,
        status: status,
        lastIndexedAt: lastIndexedAt,
        docCount: docCount,
        chunkCount: chunkCount,
        lastError: lastError,
        createdAt: createdAt,
        job: job,
      );

  factory KnowledgeSourceResponse.fromJson(Map<String, dynamic> json) =>
      KnowledgeSourceResponse(
        tenant: (json['tenant'] ?? '') as String,
        id: (json['id'] ?? '') as String,
        version: (json['version'] as num?)?.toInt() ?? 1,
        name: (json['name'] ?? '') as String,
        locationKind: (json['locationKind'] ?? 'INLINE') as String,
        location: json['location'] == null
            ? const {}
            : Map<String, dynamic>.from(json['location']),
        ingestion: json['ingestion'] == null
            ? const {}
            : Map<String, dynamic>.from(json['ingestion']),
        retrieval: json['retrieval'] == null
            ? const {}
            : Map<String, dynamic>.from(json['retrieval']),
        access: json['access'] == null
            ? const {}
            : Map<String, dynamic>.from(json['access']),
        status: (json['status'] ?? 'UNINDEXED') as String,
        lastIndexedAt: json['lastIndexedAt']?.toString(),
        docCount: (json['docCount'] as num?)?.toInt() ?? 0,
        chunkCount: (json['chunkCount'] as num?)?.toInt() ?? 0,
        lastError: json['lastError'] as String?,
        createdAt: json['createdAt']?.toString(),
      );
}

/// The async ingestion job behind an uploaded source, from
/// `GET /knowledge-sources/{id}/status` (`jobState`, `jobAttempts`, …).
class KnowledgeIngestionJob {
  const KnowledgeIngestionJob({required this.state, this.attempts = 0, this.maxAttempts = 0, this.runAfter, this.lastError});

  /// QUEUED | RUNNING | DONE | FAILED
  final String state;
  final int attempts;
  final int maxAttempts;
  final String? runAfter;
  final String? lastError;

  bool get active => state == 'QUEUED' || state == 'RUNNING';

  static KnowledgeIngestionJob? fromStatus(Map<String, dynamic> json) {
    final state = json['jobState'];
    if (state is! String) return null;
    return KnowledgeIngestionJob(
      state: state,
      attempts: (json['jobAttempts'] as num?)?.toInt() ?? 0,
      maxAttempts: (json['jobMaxAttempts'] as num?)?.toInt() ?? 0,
      runAfter: json['jobRunAfter']?.toString(),
      lastError: json['jobLastError'] as String?,
    );
  }
}

/// A file stored via the upload endpoint — the reference the UI drops into a
/// source's `location.files` for `locationKind = "UPLOADED"`. Bytes live in the
/// backend BlobStore; this is just the opaque handle + display metadata.
class UploadedFileRef {
  UploadedFileRef({
    required this.blobId,
    required this.filename,
    required this.contentType,
    required this.sizeBytes,
    required this.sha256,
  });

  final String blobId;
  final String filename;
  final String contentType;
  final int sizeBytes;
  final String sha256;

  factory UploadedFileRef.fromJson(Map<String, dynamic> json) => UploadedFileRef(
        blobId: (json['blobId'] ?? '') as String,
        filename: (json['filename'] ?? '') as String,
        contentType: (json['contentType'] ?? '') as String,
        sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
        sha256: (json['sha256'] ?? '') as String,
      );

  Map<String, dynamic> toJson() => {
        'blobId': blobId,
        'filename': filename,
        'contentType': contentType,
        'sizeBytes': sizeBytes,
        'sha256': sha256,
      };

  /// Human-friendly size, e.g. "1.4 MB".
  String get prettySize {
    if (sizeBytes < 1024) return '$sizeBytes B';
    if (sizeBytes < 1024 * 1024) return '${(sizeBytes / 1024).toStringAsFixed(1)} KB';
    return '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

class CreateKnowledgeSourceRequest {
  CreateKnowledgeSourceRequest({
    this.tenant = 'default',
    required this.id,
    required this.name,
    required this.locationKind,
    this.location = const {},
    this.ingestion = const {},
    this.retrieval = const {},
    this.access = const {},
  });

  final String tenant;
  final String id;
  final String name;
  final String locationKind;
  final Map<String, dynamic> location;
  final Map<String, dynamic> ingestion;
  final Map<String, dynamic> retrieval;
  final Map<String, dynamic> access;

  Map<String, dynamic> toJson() => {
        'tenant': tenant,
        'id': id,
        'name': name,
        'locationKind': locationKind,
        'location': location,
        'ingestion': ingestion,
        'retrieval': retrieval,
        'access': access,
      };
}

class ReindexResponse {
  ReindexResponse({
    required this.docsAdded,
    required this.chunksAdded,
    required this.totalDocs,
    required this.totalChunks,
    this.error,
  });
  final int docsAdded;
  final int chunksAdded;
  final int totalDocs;
  final int totalChunks;
  final String? error;

  factory ReindexResponse.fromJson(Map<String, dynamic> json) => ReindexResponse(
        docsAdded: (json['docsAdded'] as num?)?.toInt() ?? 0,
        chunksAdded: (json['chunksAdded'] as num?)?.toInt() ?? 0,
        totalDocs: (json['totalDocs'] as num?)?.toInt() ?? 0,
        totalChunks: (json['totalChunks'] as num?)?.toInt() ?? 0,
        error: json['error'] as String?,
      );
}

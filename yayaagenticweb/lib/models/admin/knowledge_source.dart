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

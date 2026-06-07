/// Read-only snapshot rendered in the playground right-side inspector.
/// Plain classes (no freezed) — the shape is mostly opaque maps, so the
/// generated boilerplate would just be a dead weight.
class InspectorSnapshot {
  InspectorSnapshot({
    this.intent,
    this.workingMemory = const {},
    this.lastPrompt,
    this.lastDenial,
    this.lastRetrieval,
  });

  final IntentSnapshot? intent;
  final Map<String, dynamic> workingMemory;
  final PromptSnapshot? lastPrompt;
  final DenialSnapshot? lastDenial;
  final RetrievalSnapshot? lastRetrieval;

  factory InspectorSnapshot.fromJson(Map<String, dynamic> json) => InspectorSnapshot(
        intent: json['intent'] == null
            ? null
            : IntentSnapshot.fromJson(Map<String, dynamic>.from(json['intent'])),
        workingMemory: json['workingMemory'] == null
            ? const {}
            : Map<String, dynamic>.from(json['workingMemory']),
        lastPrompt: json['lastPrompt'] == null
            ? null
            : PromptSnapshot.fromJson(Map<String, dynamic>.from(json['lastPrompt'])),
        lastDenial: json['lastDenial'] == null
            ? null
            : DenialSnapshot.fromJson(Map<String, dynamic>.from(json['lastDenial'])),
        lastRetrieval: json['lastRetrieval'] == null
            ? null
            : RetrievalSnapshot.fromJson(Map<String, dynamic>.from(json['lastRetrieval'])),
      );
}

class RetrievalSnapshot {
  RetrievalSnapshot({
    this.sourcesConsidered = const [],
    this.sourcesDenied = const [],
    this.rewrittenQuery,
    this.latencyMs = 0,
    this.chunks = const [],
  });
  final List<String> sourcesConsidered;
  final List<String> sourcesDenied;
  final String? rewrittenQuery;
  final int latencyMs;
  final List<ChunkSnapshot> chunks;

  factory RetrievalSnapshot.fromJson(Map<String, dynamic> json) => RetrievalSnapshot(
        sourcesConsidered: (json['sourcesConsidered'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(),
        sourcesDenied: (json['sourcesDenied'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(),
        rewrittenQuery: json['rewrittenQuery'] as String?,
        latencyMs: (json['latencyMs'] as num?)?.toInt() ?? 0,
        chunks: (json['chunks'] as List? ?? const [])
            .map((e) => ChunkSnapshot.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class ChunkSnapshot {
  ChunkSnapshot({
    required this.chunkId,
    required this.source,
    required this.score,
    required this.snippet,
    this.metadata = const {},
  });
  final String chunkId;
  final String source;
  final double score;
  final String snippet;
  final Map<String, dynamic> metadata;

  factory ChunkSnapshot.fromJson(Map<String, dynamic> json) => ChunkSnapshot(
        chunkId: (json['chunkId'] ?? '') as String,
        source: (json['source'] ?? '') as String,
        score: (json['score'] as num?)?.toDouble() ?? 0,
        snippet: (json['snippet'] ?? '') as String,
        metadata: json['metadata'] == null
            ? const {}
            : Map<String, dynamic>.from(json['metadata']),
      );
}

class IntentSnapshot {
  IntentSnapshot({this.label, this.slots = const {}, this.parkedStack = const []});

  final String? label;
  final Map<String, dynamic> slots;
  final List<ParkedIntent> parkedStack;

  factory IntentSnapshot.fromJson(Map<String, dynamic> json) => IntentSnapshot(
        label: json['label'] as String?,
        slots: json['slots'] == null ? const {} : Map<String, dynamic>.from(json['slots']),
        parkedStack: (json['parkedStack'] as List? ?? const [])
            .map((e) => ParkedIntent.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class ParkedIntent {
  ParkedIntent({this.label, this.slots = const {}, this.reason});
  final String? label;
  final Map<String, dynamic> slots;
  final String? reason;

  factory ParkedIntent.fromJson(Map<String, dynamic> json) => ParkedIntent(
        label: json['label'] as String?,
        slots: json['slots'] == null ? const {} : Map<String, dynamic>.from(json['slots']),
        reason: json['reason'] as String?,
      );
}

class PromptSnapshot {
  PromptSnapshot({required this.cacheablePrefix, required this.variableSuffix});
  final String cacheablePrefix;
  final String variableSuffix;

  factory PromptSnapshot.fromJson(Map<String, dynamic> json) => PromptSnapshot(
        cacheablePrefix: (json['cacheablePrefix'] ?? '') as String,
        variableSuffix: (json['variableSuffix'] ?? '') as String,
      );
}

class DenialSnapshot {
  DenialSnapshot({
    this.toolId,
    this.userReason,
    this.auditReason,
    this.policyTrace = const {},
    this.args = const {},
    this.at,
  });
  final String? toolId;
  final String? userReason;
  final String? auditReason;
  final Map<String, dynamic> policyTrace;
  final Map<String, dynamic> args;
  final String? at;

  factory DenialSnapshot.fromJson(Map<String, dynamic> json) => DenialSnapshot(
        toolId: json['toolId'] as String?,
        userReason: json['userReason'] as String?,
        auditReason: json['auditReason'] as String?,
        policyTrace: json['policyTrace'] == null
            ? const {}
            : Map<String, dynamic>.from(json['policyTrace']),
        args: json['args'] == null ? const {} : Map<String, dynamic>.from(json['args']),
        at: json['at'] as String?,
      );
}

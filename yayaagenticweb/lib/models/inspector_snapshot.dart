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
    this.toolActivity = const [],
    this.authFlow,
  });

  final IntentSnapshot? intent;
  final Map<String, dynamic> workingMemory;
  final PromptSnapshot? lastPrompt;
  final DenialSnapshot? lastDenial;
  final RetrievalSnapshot? lastRetrieval;
  final List<ToolActivitySnapshot> toolActivity;
  final AuthFlowSnapshot? authFlow;

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
        toolActivity: (json['toolActivity'] as List? ?? const [])
            .map((e) => ToolActivitySnapshot.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
        authFlow: json['authFlow'] == null
            ? null
            : AuthFlowSnapshot.fromJson(Map<String, dynamic>.from(json['authFlow'])),
      );
}

class AuthFlowSnapshot {
  AuthFlowSnapshot({
    this.binding,
    this.resolverCalls = const [],
    this.verification,
    this.sessionToken,
    this.hints = const [],
  });
  final BindingStageSnapshot? binding;
  final List<ResolverCallSnapshot> resolverCalls;
  final VerificationSnapshot? verification;
  final String? sessionToken;
  final List<AuthHintSnapshot> hints;

  factory AuthFlowSnapshot.fromJson(Map<String, dynamic> json) => AuthFlowSnapshot(
        binding: json['binding'] == null
            ? null
            : BindingStageSnapshot.fromJson(Map<String, dynamic>.from(json['binding'])),
        resolverCalls: (json['resolverCalls'] as List? ?? const [])
            .map((e) => ResolverCallSnapshot.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
        verification: json['verification'] == null
            ? null
            : VerificationSnapshot.fromJson(Map<String, dynamic>.from(json['verification'])),
        sessionToken: json['sessionToken'] as String?,
        hints: (json['hints'] as List? ?? const [])
            .map((e) => AuthHintSnapshot.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class BindingStageSnapshot {
  BindingStageSnapshot({this.bindingId, this.profileId, this.resolverId, this.onUnresolved});
  final String? bindingId;
  final String? profileId;
  final int? resolverId;
  final String? onUnresolved;

  bool get hasResolver => resolverId != null;

  factory BindingStageSnapshot.fromJson(Map<String, dynamic> json) => BindingStageSnapshot(
        bindingId: json['bindingId'] as String?,
        profileId: json['profileId'] as String?,
        resolverId: (json['resolverId'] as num?)?.toInt(),
        onUnresolved: json['onUnresolved'] as String?,
      );
}

class ResolverCallSnapshot {
  ResolverCallSnapshot({
    this.resolver,
    this.reason,
    this.cacheState,
    this.status,
    this.httpStatus,
    this.elapsedMs,
    this.errorSummary,
    this.startedAt,
  });
  final String? resolver;
  final String? reason;
  final String? cacheState;
  final String? status;
  final int? httpStatus;
  final int? elapsedMs;
  final String? errorSummary;
  final String? startedAt;

  bool get isOk => status == 'ok';

  factory ResolverCallSnapshot.fromJson(Map<String, dynamic> json) => ResolverCallSnapshot(
        resolver: json['resolver'] as String?,
        reason: json['reason'] as String?,
        cacheState: json['cacheState'] as String?,
        status: json['status'] as String?,
        httpStatus: (json['httpStatus'] as num?)?.toInt(),
        elapsedMs: (json['elapsedMs'] as num?)?.toInt(),
        errorSummary: json['errorSummary'] as String?,
        startedAt: json['startedAt'] as String?,
      );
}

class VerificationSnapshot {
  VerificationSnapshot({this.decision, this.detail});
  final String? decision;
  final String? detail;

  bool get isVerified => decision == 'JWT_VERIFIED';

  factory VerificationSnapshot.fromJson(Map<String, dynamic> json) => VerificationSnapshot(
        decision: json['decision'] as String?,
        detail: json['detail'] as String?,
      );
}

class AuthHintSnapshot {
  AuthHintSnapshot({required this.severity, required this.message});
  final String severity; // info | warn | fail
  final String message;

  factory AuthHintSnapshot.fromJson(Map<String, dynamic> json) => AuthHintSnapshot(
        severity: (json['severity'] ?? 'info') as String,
        message: (json['message'] ?? '') as String,
      );
}

class ToolActivitySnapshot {
  ToolActivitySnapshot({
    required this.callId,
    required this.toolId,
    this.args = const {},
    required this.status,
    this.result,
    this.error,
    this.authForwarding,
    this.authHeaderPresent,
    this.httpStatus,
  });
  final String callId;
  final String toolId;
  final Map<String, dynamic> args;
  final String status;
  final Object? result;
  final String? error;
  final String? authForwarding;
  final bool? authHeaderPresent;
  final int? httpStatus;

  bool get isOk => status == 'OK';
  bool get isPending => status == 'PENDING';

  factory ToolActivitySnapshot.fromJson(Map<String, dynamic> json) => ToolActivitySnapshot(
        callId: (json['callId'] ?? '') as String,
        toolId: (json['toolId'] ?? '') as String,
        args: json['args'] == null ? const {} : Map<String, dynamic>.from(json['args']),
        status: (json['status'] ?? '') as String,
        result: json['result'],
        error: json['error'] as String?,
        authForwarding: json['authForwarding'] as String?,
        authHeaderPresent: json['authHeaderPresent'] as bool?,
        httpStatus: (json['httpStatus'] as num?)?.toInt(),
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

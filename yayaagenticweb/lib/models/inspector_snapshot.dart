/// Read-only snapshot rendered in the playground right-side inspector.
/// Plain classes (no freezed) — the shape is mostly opaque maps, so the
/// generated boilerplate would just be a dead weight.
class InspectorSnapshot {
  InspectorSnapshot({
    this.intent,
    this.workingMemory = const {},
    this.lastPrompt,
    this.lastDenial,
  });

  final IntentSnapshot? intent;
  final Map<String, dynamic> workingMemory;
  final PromptSnapshot? lastPrompt;
  final DenialSnapshot? lastDenial;

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

/// Mutable, editable drafts backing the visual Session Start Flow builder.
/// Each draft (de)serializes to exactly the JSON the backend's sealed
/// step/value/target unions expect (a `kind` discriminator per variant), so the
/// UI never asks the operator to write JSON. See `docs/design/session-start-flows.md`.
library;

import '../../../models/admin/start_flow.dart';

// ---- shared vocab (kept in sync with the backend sealed types) ----------

const kSourceKinds = <String, String>{
  'Channel': 'From the conversation',
  'Entity': 'From a saved value',
  'StepOutput': 'From an earlier step',
  'Principal': 'From the signed-in user',
  'Literal': 'A fixed value',
};

const kChannelFields = <String>['endUserPhone', 'endUserLocale', 'endUserChannel'];

const kOps = <String, String>{
  'EQ': 'equals',
  'NEQ': 'does not equal',
  'EXISTS': 'is present',
  'ABSENT': 'is absent',
  'TRUTHY': 'is true',
  'GT': 'is greater than',
  'LT': 'is less than',
  'CONTAINS': 'contains',
};

const kBindingModes = <String, String>{
  'FILL_IF_MISSING': 'Fill if missing',
  'OVERRIDE': 'Always override',
  'REQUIRED': 'Required',
};

const kOnError = <String, String>{
  'ABORT': 'Stop the flow (fallback greeting)',
  'CONTINUE': 'Skip and continue',
  'RETRY': 'Retry then stop',
};

const kGreetingModes = <String, String>{
  'STATIC_TEMPLATE': 'Write it myself (template)',
  'LLM_COMPOSED': 'Let the assistant write it',
  'HYBRID': 'Blend (template + assistant)',
};

const kTargetKinds = <String, String>{
  'Step': 'Go to another step',
  'End': 'Finish',
  'Abort': 'Stop (fallback)',
};

/// Coerce a text field into the scalar the backend expects (bool / num / string).
dynamic coerceScalar(String raw) {
  final t = raw.trim();
  if (t == 'true') return true;
  if (t == 'false') return false;
  final n = num.tryParse(t);
  if (n != null) return n;
  return raw;
}

// ---- value source ------------------------------------------------------

class ValueSourceDraft {
  ValueSourceDraft({
    this.kind = 'Channel',
    this.channelField = 'endUserPhone',
    this.entityName = '',
    this.stepKey = '',
    this.jsonPointer = '',
    this.claimPath = '',
    this.literal = '',
  });

  String kind;
  String channelField;
  String entityName;
  String stepKey;
  String jsonPointer;
  String claimPath;
  String literal;

  factory ValueSourceDraft.fromJson(Map<String, dynamic> j) => ValueSourceDraft(
        kind: (j['kind'] ?? 'Channel') as String,
        channelField: (j['field'] ?? 'endUserPhone') as String,
        entityName: (j['entityName'] ?? '') as String,
        stepKey: (j['stepKey'] ?? '') as String,
        jsonPointer: (j['jsonPointer'] ?? '') as String,
        claimPath: (j['claimPath'] ?? '') as String,
        literal: j['value'] == null ? '' : j['value'].toString(),
      );

  Map<String, dynamic> toJson() {
    switch (kind) {
      case 'Literal':
        return {'kind': 'Literal', 'value': coerceScalar(literal)};
      case 'Principal':
        return {'kind': 'Principal', 'claimPath': claimPath};
      case 'Entity':
        return {'kind': 'Entity', 'entityName': entityName};
      case 'StepOutput':
        return {'kind': 'StepOutput', 'stepKey': stepKey, 'jsonPointer': jsonPointer};
      case 'Channel':
      default:
        return {'kind': 'Channel', 'field': channelField};
    }
  }

  String summary() => switch (kind) {
        'Literal' => '"$literal"',
        'Principal' => 'user.$claimPath',
        'Entity' => entityName,
        'StepOutput' => '$stepKey$jsonPointer',
        _ => 'channel.$channelField',
      };
}

// ---- capture -----------------------------------------------------------

class CaptureDraft {
  CaptureDraft({
    this.jsonPointer = '/',
    this.targetKind = 'Entity',
    this.name = '',
    this.visibleToLlm = true,
  });

  String jsonPointer;
  String targetKind; // Entity | StepVar
  String name;
  bool visibleToLlm;

  factory CaptureDraft.fromJson(Map<String, dynamic> j) {
    final t = (j['target'] is Map) ? Map<String, dynamic>.from(j['target'] as Map) : const {};
    return CaptureDraft(
      jsonPointer: (j['jsonPointer'] ?? '/') as String,
      targetKind: (t['kind'] ?? 'Entity') as String,
      name: (t['name'] ?? '') as String,
      visibleToLlm: t['visibleToLlm'] != false,
    );
  }

  Map<String, dynamic> toJson() => {
        'jsonPointer': jsonPointer,
        'target': targetKind == 'StepVar'
            ? {'kind': 'StepVar', 'name': name}
            : {'kind': 'Entity', 'name': name, 'visibleToLlm': visibleToLlm},
      };
}

// ---- branch (condition + target) ---------------------------------------

class BranchDraft {
  BranchDraft({
    this.always = false,
    ValueSourceDraft? left,
    this.op = 'EQ',
    this.right = 'true',
    this.targetKind = 'End',
    this.stepKey = '',
    this.outcomeLabel = 'success',
    this.reason = '',
  }) : left = left ?? ValueSourceDraft(kind: 'StepOutput');

  bool always;
  ValueSourceDraft left;
  String op;
  String right;
  String targetKind; // Step | End | Abort
  String stepKey;
  String outcomeLabel;
  String reason;

  factory BranchDraft.fromJson(Map<String, dynamic> j) {
    final when = (j['when'] is Map) ? Map<String, dynamic>.from(j['when'] as Map) : const {};
    final target = (j['target'] is Map) ? Map<String, dynamic>.from(j['target'] as Map) : const {};
    final leftMap = (when['left'] is Map) ? Map<String, dynamic>.from(when['left'] as Map) : null;
    final isAlways = (when['op'] == 'TRUTHY') && (leftMap?['kind'] == 'Literal');
    return BranchDraft(
      always: isAlways,
      left: leftMap == null ? ValueSourceDraft(kind: 'StepOutput') : ValueSourceDraft.fromJson(leftMap),
      op: (when['op'] ?? 'EQ') as String,
      right: when['right'] == null ? '' : when['right'].toString(),
      targetKind: (target['kind'] ?? 'End') as String,
      stepKey: (target['key'] ?? '') as String,
      outcomeLabel: (target['outcomeLabel'] ?? 'success') as String,
      reason: (target['reason'] ?? '') as String,
    );
  }

  Map<String, dynamic> toJson() {
    final when = always
        ? {'left': {'kind': 'Literal', 'value': true}, 'op': 'TRUTHY'}
        : {'left': left.toJson(), 'op': op, if (op != 'EXISTS' && op != 'ABSENT' && op != 'TRUTHY') 'right': coerceScalar(right)};
    final target = switch (targetKind) {
      'Step' => {'kind': 'Step', 'key': stepKey},
      'Abort' => {'kind': 'Abort', 'reason': reason},
      _ => {'kind': 'End', 'outcomeLabel': outcomeLabel},
    };
    return {'when': when, 'target': target};
  }
}

// ---- steps -------------------------------------------------------------

sealed class StepDraft {
  StepDraft(this.key);
  String key;
  String get kind;
  Map<String, dynamic> toJson();

  static StepDraft fromJson(Map<String, dynamic> j) {
    final key = (j['key'] ?? '') as String;
    switch (j['kind']) {
      case 'Elicit':
        return ElicitDraft.fromJson(j, key);
      case 'Decision':
        return DecisionDraft.fromJson(j, key);
      case 'RunTool':
      default:
        return RunToolDraft.fromJson(j, key);
    }
  }
}

class InputDraft {
  InputDraft({this.argName = '', ValueSourceDraft? source, this.mode = 'FILL_IF_MISSING'})
      : source = source ?? ValueSourceDraft();
  String argName;
  ValueSourceDraft source;
  String mode;

  factory InputDraft.fromJson(Map<String, dynamic> j) => InputDraft(
        argName: (j['argName'] ?? '') as String,
        source: ValueSourceDraft.fromJson(
            j['source'] is Map ? Map<String, dynamic>.from(j['source'] as Map) : const {}),
        mode: (j['mode'] ?? 'FILL_IF_MISSING') as String,
      );

  Map<String, dynamic> toJson() =>
      {'argName': argName, 'source': source.toJson(), 'mode': mode};
}

class RunToolDraft extends StepDraft {
  RunToolDraft(super.key,
      {this.toolId = '',
      List<InputDraft>? inputs,
      List<CaptureDraft>? captures,
      List<BranchDraft>? next,
      this.onErrorMode = 'ABORT',
      this.maxRetries = 0})
      : inputs = inputs ?? [],
        captures = captures ?? [],
        next = next ?? [];

  String toolId;
  List<InputDraft> inputs;
  List<CaptureDraft> captures;
  List<BranchDraft> next;
  String onErrorMode;
  int maxRetries;

  @override
  String get kind => 'RunTool';

  factory RunToolDraft.fromJson(Map<String, dynamic> j, String key) {
    final tool = (j['tool'] is Map) ? Map<String, dynamic>.from(j['tool'] as Map) : const {};
    final onErr = (j['onError'] is Map) ? Map<String, dynamic>.from(j['onError'] as Map) : const {};
    return RunToolDraft(
      key,
      toolId: (tool['value'] ?? '') as String,
      inputs: ((j['inputs'] as List?) ?? const [])
          .map((e) => InputDraft.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
      captures: ((j['captures'] as List?) ?? const [])
          .map((e) => CaptureDraft.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
      next: ((j['next'] as List?) ?? const [])
          .map((e) => BranchDraft.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
      onErrorMode: (onErr['onError'] ?? 'ABORT') as String,
      maxRetries: (onErr['maxRetries'] as num?)?.toInt() ?? 0,
    );
  }

  @override
  Map<String, dynamic> toJson() => {
        'kind': 'RunTool',
        'key': key,
        'tool': {'value': toolId},
        'inputs': inputs.map((e) => e.toJson()).toList(),
        'captures': captures.map((e) => e.toJson()).toList(),
        'next': next.map((e) => e.toJson()).toList(),
        'onError': {'onError': onErrorMode, 'maxRetries': maxRetries},
      };
}

class ElicitDraft extends StepDraft {
  ElicitDraft(super.key,
      {this.promptTemplate = '',
      List<String>? quickReplies,
      this.uiHintKind = '',
      this.captureKind = 'StepVar',
      this.captureName = '',
      this.visibleToLlm = true,
      List<BranchDraft>? next})
      : quickReplies = quickReplies ?? [],
        next = next ?? [];

  String promptTemplate;
  List<String> quickReplies;
  String uiHintKind;
  String captureKind; // Entity | StepVar
  String captureName;
  bool visibleToLlm;
  List<BranchDraft> next;

  @override
  String get kind => 'Elicit';

  factory ElicitDraft.fromJson(Map<String, dynamic> j, String key) {
    final ct = (j['captureTarget'] is Map)
        ? Map<String, dynamic>.from(j['captureTarget'] as Map)
        : const {};
    return ElicitDraft(
      key,
      promptTemplate: (j['promptTemplate'] ?? '') as String,
      quickReplies: ((j['quickReplyTemplates'] as List?) ?? const []).map((e) => e.toString()).toList(),
      uiHintKind: (j['uiHintKind'] ?? '') as String,
      captureKind: (ct['kind'] ?? 'StepVar') as String,
      captureName: (ct['name'] ?? '') as String,
      visibleToLlm: ct['visibleToLlm'] != false,
      next: ((j['next'] as List?) ?? const [])
          .map((e) => BranchDraft.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }

  @override
  Map<String, dynamic> toJson() => {
        'kind': 'Elicit',
        'key': key,
        'promptTemplate': promptTemplate,
        'quickReplyTemplates': quickReplies,
        if (uiHintKind.isNotEmpty) 'uiHintKind': uiHintKind,
        'uiHintPayload': const <String, dynamic>{},
        'captureTarget': captureKind == 'Entity'
            ? {'kind': 'Entity', 'name': captureName, 'visibleToLlm': visibleToLlm}
            : {'kind': 'StepVar', 'name': captureName},
        'next': next.map((e) => e.toJson()).toList(),
      };
}

class DecisionDraft extends StepDraft {
  DecisionDraft(super.key,
      {List<BranchDraft>? branches,
      this.defaultKind = 'End',
      this.defaultStep = '',
      this.defaultOutcome = 'fallback',
      this.defaultReason = ''})
      : branches = branches ?? [];

  List<BranchDraft> branches;
  String defaultKind;
  String defaultStep;
  String defaultOutcome;
  String defaultReason;

  @override
  String get kind => 'Decision';

  factory DecisionDraft.fromJson(Map<String, dynamic> j, String key) {
    final def = (j['defaultTarget'] is Map)
        ? Map<String, dynamic>.from(j['defaultTarget'] as Map)
        : const {};
    return DecisionDraft(
      key,
      branches: ((j['branches'] as List?) ?? const [])
          .map((e) => BranchDraft.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
      defaultKind: (def['kind'] ?? 'End') as String,
      defaultStep: (def['key'] ?? '') as String,
      defaultOutcome: (def['outcomeLabel'] ?? 'fallback') as String,
      defaultReason: (def['reason'] ?? '') as String,
    );
  }

  @override
  Map<String, dynamic> toJson() => {
        'kind': 'Decision',
        'key': key,
        'branches': branches.map((e) => e.toJson()).toList(),
        'defaultTarget': switch (defaultKind) {
          'Step' => {'kind': 'Step', 'key': defaultStep},
          'Abort' => {'kind': 'Abort', 'reason': defaultReason},
          _ => {'kind': 'End', 'outcomeLabel': defaultOutcome},
        },
      };
}

// ---- greeting ----------------------------------------------------------

class GreetingDraft {
  GreetingDraft({
    this.mode = 'STATIC_TEMPLATE',
    this.successTemplate = 'Welcome back, {customerName}!',
    this.fallbackGreeting = 'Hi! How can I help you today?',
    List<String>? quickReplies,
    this.instruction = 'Greet the returning customer warmly by name and offer to help — one sentence.',
    List<String>? briefingEntities,
  })  : quickReplies = quickReplies ?? [],
        briefingEntities = briefingEntities ?? [];

  String mode;
  String successTemplate;
  String fallbackGreeting;
  List<String> quickReplies;
  String instruction;
  List<String> briefingEntities;

  bool get isLlm => mode == 'LLM_COMPOSED' || mode == 'HYBRID';

  factory GreetingDraft.fromJson(Map<String, dynamic> j) {
    final templates = (j['outcomeGreetingTemplates'] is Map)
        ? Map<String, dynamic>.from(j['outcomeGreetingTemplates'] as Map)
        : const {};
    final llm = (j['llmOpening'] is Map) ? Map<String, dynamic>.from(j['llmOpening'] as Map) : const {};
    return GreetingDraft(
      mode: (j['mode'] ?? 'STATIC_TEMPLATE') as String,
      successTemplate: (templates['success'] ?? '') as String,
      fallbackGreeting: (j['fallbackGreeting'] ?? '') as String,
      quickReplies: ((j['quickReplyTemplates'] as List?) ?? const []).map((e) => e.toString()).toList(),
      instruction: (llm['instruction'] ?? '') as String,
      briefingEntities:
          ((llm['briefingEntities'] as List?) ?? const []).map((e) => e.toString()).toList(),
    );
  }

  Map<String, dynamic> toJson() => {
        'mode': mode,
        'outcomeGreetingTemplates': {
          if (successTemplate.isNotEmpty) 'success': successTemplate,
        },
        'fallbackGreeting': fallbackGreeting,
        'quickReplyTemplates': quickReplies,
        if (isLlm)
          'llmOpening': {
            'instruction': instruction,
            'briefingEntities': briefingEntities,
            'quickReplyHints': const <String>[],
            'stream': false,
          },
      };
}

// ---- whole flow --------------------------------------------------------

class FlowDraft {
  FlowDraft({
    this.id = '',
    this.name = '',
    this.description = '',
    List<StepDraft>? steps,
    GreetingDraft? greeting,
  })  : steps = steps ?? [],
        greeting = greeting ?? GreetingDraft();

  String id;
  String name;
  String description;
  List<StepDraft> steps;
  GreetingDraft greeting;

  /// Entity names captured anywhere in the flow — offered as briefing options
  /// and "saved value" sources.
  List<String> get knownEntities {
    final names = <String>{};
    for (final s in steps) {
      if (s is RunToolDraft) {
        for (final c in s.captures) {
          if (c.targetKind == 'Entity' && c.name.isNotEmpty) names.add(c.name);
        }
      } else if (s is ElicitDraft && s.captureKind == 'Entity' && s.captureName.isNotEmpty) {
        names.add(s.captureName);
      }
    }
    return names.toList();
  }

  List<String> get stepKeys => steps.map((s) => s.key).where((k) => k.isNotEmpty).toList();

  factory FlowDraft.fromResponse(StartFlowResponse f) => FlowDraft(
        id: f.id,
        name: f.name,
        description: f.description ?? '',
        steps: f.steps
            .map((e) => StepDraft.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        greeting: GreetingDraft.fromJson(f.greeting),
      );

  StartFlowRequest toRequest({required bool publish}) => StartFlowRequest(
        id: id.trim(),
        name: name.trim(),
        description: description.trim().isEmpty ? null : description.trim(),
        steps: steps.map((s) => s.toJson()).toList(),
        greeting: greeting.toJson(),
        policy: const {}, // backend applies FlowPolicy.defaults()
        publish: publish,
      );
}

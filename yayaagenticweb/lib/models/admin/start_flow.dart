/// Wire models for Session Start Flows
/// (`/v1/admin/tenants/{tenantId}/start-flows`). Hand-rolled to match the
/// existing admin-model pattern. The `steps`, `greeting`, and `policy` blocks
/// ride as raw JSON (the backend (de)serializes the sealed step/value/target
/// unions via their `kind` discriminator), so the builder edits them directly.
/// See `docs/design/session-start-flows.md` §14.
library;

class StartFlowResponse {
  StartFlowResponse({
    required this.id,
    required this.version,
    required this.tenantId,
    required this.name,
    this.description,
    this.steps = const [],
    this.greeting = const {},
    this.policy = const {},
    required this.status,
    this.createdAt,
    this.updatedAt,
  });

  final String id;
  final int version;
  final String tenantId;
  final String name;
  final String? description;
  final List<dynamic> steps;
  final Map<String, dynamic> greeting;
  final Map<String, dynamic> policy;
  final String status; // DRAFT | ACTIVE | ARCHIVED
  final String? createdAt;
  final String? updatedAt;

  bool get isActive => status == 'ACTIVE';
  bool get isDraft => status == 'DRAFT';
  bool get isArchived => status == 'ARCHIVED';

  int get stepCount => steps.length;

  /// Convenience: the greeting mode operators most care about.
  String get greetingMode => (greeting['mode'] ?? 'STATIC_TEMPLATE') as String;

  factory StartFlowResponse.fromJson(Map<String, dynamic> json) => StartFlowResponse(
        id: (json['id'] ?? '') as String,
        version: (json['version'] as num?)?.toInt() ?? 1,
        tenantId: (json['tenantId'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        description: json['description'] as String?,
        steps: (json['steps'] as List?) ?? const [],
        greeting: json['greeting'] is Map
            ? Map<String, dynamic>.from(json['greeting'] as Map)
            : const {},
        policy: json['policy'] is Map
            ? Map<String, dynamic>.from(json['policy'] as Map)
            : const {},
        status: (json['status'] ?? 'DRAFT') as String,
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
      );
}

class StartFlowRequest {
  StartFlowRequest({
    required this.id,
    required this.name,
    this.description,
    required this.steps,
    required this.greeting,
    required this.policy,
    this.publish = false,
  });

  final String id;
  final String name;
  final String? description;
  final List<dynamic> steps;
  final Map<String, dynamic> greeting;
  final Map<String, dynamic> policy;
  final bool publish;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        if (description != null && description!.isNotEmpty) 'description': description,
        'steps': steps,
        'greeting': greeting,
        'policy': policy,
        'publish': publish,
      };

  /// Update body — creates a new version (backend versions immutably).
  Map<String, dynamic> toUpdateJson() => {
        'name': name,
        if (description != null) 'description': description,
        'steps': steps,
        'greeting': greeting,
        'policy': policy,
      };
}

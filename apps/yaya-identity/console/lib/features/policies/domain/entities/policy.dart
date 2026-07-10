import 'dart:convert';

import 'package:equatable/equatable.dart';

class Policy extends Equatable {
  const Policy({
    required this.id,
    required this.name,
    required this.effect,
    required this.priority,
    this.active = true,
    this.targetJson,
    this.conditionJson,
    this.userReason,
    this.auditReason,
  });

  final String id;
  final String name;
  final String effect; // PERMIT | DENY
  final int priority;
  final bool active;
  final String? targetJson;
  final String? conditionJson;
  final String? userReason;
  final String? auditReason;

  /// The actions this policy targets, parsed out of the target JSON.
  List<String> get targetActions {
    if (targetJson == null || targetJson!.isEmpty) return const [];
    try {
      final decoded = jsonDecode(targetJson!);
      if (decoded is Map && decoded['actions'] is List) {
        return (decoded['actions'] as List).cast<String>();
      }
    } on FormatException {
      // fall through — malformed target renders as no chips
    }
    return const [];
  }

  bool get hasCondition =>
      conditionJson != null && conditionJson!.trim().isNotEmpty && conditionJson != 'null';

  @override
  List<Object?> get props => [
        id, name, effect, priority, active,
        targetJson, conditionJson, userReason, auditReason,
      ];
}

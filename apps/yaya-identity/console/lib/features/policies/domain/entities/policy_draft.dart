import 'package:equatable/equatable.dart';

/// A policy to create. [targetJson]/[conditionJson] are raw JSON-logic the
/// operator typed; the data layer parses them. A DENY must carry two distinct
/// reasons (#3) — the backend enforces it, the editor surfaces the error.
class PolicyDraft extends Equatable {
  const PolicyDraft({
    required this.org,
    required this.name,
    required this.effect,
    required this.targetJson,
    this.conditionJson,
    this.priority = 0,
    this.userReason,
    this.auditReason,
  });

  final String org;
  final String name;
  final String effect;
  final String targetJson;
  final String? conditionJson;
  final int priority;
  final String? userReason;
  final String? auditReason;

  @override
  List<Object?> get props =>
      [org, name, effect, targetJson, conditionJson, priority, userReason, auditReason];
}

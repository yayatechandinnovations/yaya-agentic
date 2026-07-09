import 'package:equatable/equatable.dart';

/// A scoped role grant — a subject holds a role within a scope. When
/// [scopeType]/[scopeId] are null the grant is org-wide; otherwise it's confined
/// to that one resource (e.g. store:st_123) — the "manager of A but not B" case.
class RoleAssignmentRequest extends Equatable {
  const RoleAssignmentRequest({
    required this.org,
    required this.subjectKind, // USER | GROUP
    required this.subjectId,
    required this.roleId,
    this.scopeType,
    this.scopeId,
  });

  final String org;
  final String subjectKind;
  final String subjectId;
  final String roleId;
  final String? scopeType;
  final String? scopeId;

  @override
  List<Object?> get props => [org, subjectKind, subjectId, roleId, scopeType, scopeId];
}

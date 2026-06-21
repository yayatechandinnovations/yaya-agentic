import 'package:freezed_annotation/freezed_annotation.dart';

part 'operator.freezed.dart';
part 'operator.g.dart';

/// Mirrors the backend's `AuthController.MeResponse`. Field names match
/// what /v1/auth/me returns so Jackson's default Java-record JSON serialisation
/// (camelCase keys) lines up without a custom converter.
@freezed
class Operator with _$Operator {
  const factory Operator({
    required String subject,
    required String displayName,
    required String source,
    @Default(<String, dynamic>{}) Map<String, dynamic> attributes,
    DateTime? verifiedAt,
    @Default(<WorkspaceMembership>[]) List<WorkspaceMembership> memberships,
    @Default(false) bool platformAdmin,
  }) = _Operator;

  factory Operator.fromJson(Map<String, dynamic> json) => _$OperatorFromJson(json);
}

/// One row in the operator's workspace-membership list. Used by the
/// workspace switcher and to gate UI affordances per role.
@freezed
class WorkspaceMembership with _$WorkspaceMembership {
  const factory WorkspaceMembership({
    required String workspaceId,
    required String role,
  }) = _WorkspaceMembership;

  factory WorkspaceMembership.fromJson(Map<String, dynamic> json) =>
      _$WorkspaceMembershipFromJson(json);
}

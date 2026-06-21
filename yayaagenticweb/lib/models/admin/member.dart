import 'package:freezed_annotation/freezed_annotation.dart';

part 'member.freezed.dart';
part 'member.g.dart';

/// Mirrors backend `MembersController.MemberDto`.
@freezed
class Member with _$Member {
  const factory Member({
    required String userId,
    String? email,
    String? displayName,
    required String role,
    String? addedAt,
  }) = _Member;

  factory Member.fromJson(Map<String, dynamic> json) => _$MemberFromJson(json);
}

/// Body for `PATCH /v1/admin/workspaces/{code}/members/{userId}`.
@freezed
class MemberRoleChangeRequest with _$MemberRoleChangeRequest {
  const factory MemberRoleChangeRequest({
    required String role,
  }) = _MemberRoleChangeRequest;

  factory MemberRoleChangeRequest.fromJson(Map<String, dynamic> json) =>
      _$MemberRoleChangeRequestFromJson(json);
}

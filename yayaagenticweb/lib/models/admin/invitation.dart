import 'package:freezed_annotation/freezed_annotation.dart';

part 'invitation.freezed.dart';
part 'invitation.g.dart';

/// Mirrors backend `InvitationController.InvitationDto`.
@freezed
class Invitation with _$Invitation {
  const factory Invitation({
    required String id,
    required String workspaceId,
    required String email,
    required String role,
    String? invitedBy,
    String? expiresAt,
    String? createdAt,
    String? consumedAt,
  }) = _Invitation;

  factory Invitation.fromJson(Map<String, dynamic> json) =>
      _$InvitationFromJson(json);
}

/// Body for `POST /v1/admin/workspaces/{code}/invitations`.
@freezed
class InvitationCreateRequest with _$InvitationCreateRequest {
  const factory InvitationCreateRequest({
    required String email,
    required String role,
  }) = _InvitationCreateRequest;

  factory InvitationCreateRequest.fromJson(Map<String, dynamic> json) =>
      _$InvitationCreateRequestFromJson(json);
}

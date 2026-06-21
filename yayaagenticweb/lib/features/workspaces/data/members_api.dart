import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/api_client.dart';
import '../../../models/admin/invitation.dart';
import '../../../models/admin/member.dart';

/// Wrapper over `/v1/admin/workspaces/{code}/members` and
/// `/invitations`. Surfaces the backend's `last_owner` 409 as a typed
/// flag so the UI can render the user-safe reason verbatim.
class WorkspaceMembersApi {
  WorkspaceMembersApi(this._dio);
  final Dio _dio;

  // -- Members ---------------------------------------------------------

  Future<List<Member>> listMembers(String workspaceCode) async {
    try {
      final res = await _dio.get('/v1/admin/workspaces/$workspaceCode/members');
      return (res.data as List)
          .map((e) => Member.fromJson(_asMap(e)))
          .toList();
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<Member> changeRole({
    required String workspaceCode,
    required String userId,
    required String newRole,
  }) async {
    try {
      final res = await _dio.patch(
          '/v1/admin/workspaces/$workspaceCode/members/$userId',
          data: MemberRoleChangeRequest(role: newRole).toJson());
      return Member.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<void> remove({
    required String workspaceCode,
    required String userId,
  }) async {
    try {
      await _dio.delete('/v1/admin/workspaces/$workspaceCode/members/$userId');
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  // -- Invitations -----------------------------------------------------

  Future<List<Invitation>> listInvitations(String workspaceCode) async {
    try {
      final res = await _dio
          .get('/v1/admin/workspaces/$workspaceCode/invitations');
      return (res.data as List)
          .map((e) => Invitation.fromJson(_asMap(e)))
          .toList();
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<Invitation> invite({
    required String workspaceCode,
    required String email,
    required String role,
  }) async {
    try {
      final res = await _dio.post(
          '/v1/admin/workspaces/$workspaceCode/invitations',
          data: InvitationCreateRequest(email: email, role: role).toJson());
      return Invitation.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<void> revokeInvitation({
    required String workspaceCode,
    required String invitationId,
  }) async {
    try {
      await _dio.delete(
          '/v1/admin/workspaces/$workspaceCode/invitations/$invitationId');
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  // -- Errors ----------------------------------------------------------

  MembersApiException _toException(DioException e) {
    final data = e.response?.data;
    String? message;
    String? code;
    if (data is Map) {
      if (data['message'] is String) message = data['message'] as String;
      if (data['error'] is String) code = data['error'] as String;
    }
    return MembersApiException(
      statusCode: e.response?.statusCode,
      message: message ?? 'Network error',
      errorCode: code,
    );
  }

  static Map<String, dynamic> _asMap(dynamic raw) =>
      Map<String, dynamic>.from(raw as Map);
}

class MembersApiException implements Exception {
  MembersApiException({
    required this.statusCode,
    required this.message,
    this.errorCode,
  });
  final int? statusCode;
  final String message;
  final String? errorCode;

  /// True when the server refused because the operation would orphan the
  /// last OWNER. The {@link #message} is already user-safe — surface it
  /// verbatim.
  bool get isLastOwner => statusCode == 409 || errorCode == 'last_owner';

  /// True for an ADMIN-touching-OWNER 403.
  bool get isForbidden => statusCode == 403;

  /// True when the workspace doesn't exist OR the caller lost access.
  /// Same body either way — the UI must NOT branch on this beyond
  /// returning to the workspace list.
  bool get isNotFound =>
      statusCode == 404 || errorCode == 'workspace_not_found';

  @override
  String toString() =>
      'MembersApiException($statusCode, $errorCode): $message';
}

final workspaceMembersApiProvider =
    FutureProvider<WorkspaceMembersApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return WorkspaceMembersApi(dio);
});

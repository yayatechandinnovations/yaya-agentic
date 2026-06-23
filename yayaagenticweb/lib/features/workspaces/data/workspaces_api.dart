import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/api_client.dart';
import '../../../models/admin/workspace.dart';

/// Thin wrapper over `/v1/admin/workspaces/**`. Lives in `features/workspaces`
/// rather than the catch-all `AdminApi` because workspace CRUD is a
/// user-facing surface (filtered by membership), not the platform-admin
/// tenant view that `AdminApi.listTenants` exposes.
///
/// Errors are normalised into [WorkspaceApiException]; 404 from any of
/// these endpoints carries the existence-non-leak invariant — the caller
/// can't tell whether the workspace doesn't exist or whether they have
/// no membership.
class WorkspacesApi {
  WorkspacesApi(this._dio);
  final Dio _dio;

  Future<List<WorkspaceListItem>> listMine() async {
    try {
      final res = await _dio.get('/v1/admin/workspaces');
      return (res.data as List)
          .map((e) => WorkspaceListItem.fromJson(_asMap(e)))
          .toList();
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<Workspace> create(String displayName) async {
    try {
      final res = await _dio.post('/v1/admin/workspaces',
          data: WorkspaceCreateRequest(displayName: displayName).toJson());
      return Workspace.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<Workspace> get(String idOrCode) async {
    try {
      final res = await _dio.get('/v1/admin/workspaces/$idOrCode');
      return Workspace.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  /// Each named arg is optional — callers pass only the fields they want
  /// to change. `null` means "leave untouched"; passing an empty string
  /// for [hostBaseUrl] clears the value, and an empty list for
  /// [hostBaseUrlAllowlist] clears the allowlist.
  Future<Workspace> patch(
    String idOrCode, {
    String? displayName,
    String? hostBaseUrl,
    List<String>? hostBaseUrlAllowlist,
  }) async {
    try {
      final res = await _dio.patch(
        '/v1/admin/workspaces/$idOrCode',
        data: WorkspacePatchRequest(
          displayName: displayName,
          hostBaseUrl: hostBaseUrl,
          hostBaseUrlAllowlist: hostBaseUrlAllowlist,
        ).toJson(),
      );
      return Workspace.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  /// Soft-archive. The server requires {@code confirmCode} to match the
  /// workspace's own code exactly — the UI's typed-name guard mirrors it.
  Future<void> archive(String idOrCode, String confirmCode) async {
    try {
      await _dio.delete('/v1/admin/workspaces/$idOrCode',
          data: WorkspaceArchiveRequest(confirmCode: confirmCode).toJson());
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  WorkspaceApiException _toException(DioException e) {
    final data = e.response?.data;
    String? message;
    String? code;
    if (data is Map) {
      if (data['message'] is String) message = data['message'] as String;
      if (data['error'] is String) code = data['error'] as String;
    }
    return WorkspaceApiException(
      statusCode: e.response?.statusCode,
      message: message ?? 'Network error',
      errorCode: code,
    );
  }

  static Map<String, dynamic> _asMap(dynamic raw) =>
      Map<String, dynamic>.from(raw as Map);
}

class WorkspaceApiException implements Exception {
  WorkspaceApiException({
    required this.statusCode,
    required this.message,
    this.errorCode,
  });
  final int? statusCode;
  final String message;
  final String? errorCode;

  /// True when the response was 404 — i.e. the caller has no access or the
  /// workspace doesn't exist. The two cases are deliberately indistinguishable.
  bool get isNotFound =>
      statusCode == 404 || errorCode == 'workspace_not_found';

  @override
  String toString() => 'WorkspaceApiException($statusCode, $errorCode): $message';
}

final workspacesApiProvider = FutureProvider<WorkspacesApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return WorkspacesApi(dio);
});

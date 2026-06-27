import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/api_client.dart';
import '../../../models/admin/api_key.dart';

/// Thin wrapper over `/v1/admin/workspaces/{code}/api-keys`. Mirrors the
/// backend `ApiKeyController`. 404 carries the existence-non-leak invariant —
/// the caller can't tell "no such key" from "not in this workspace".
///
/// Note: this app authenticates with the operator cookie, NOT with the keys it
/// mints. Keys are for server-to-server callers outside this UI (design §9.3),
/// so the Dio interceptor is unchanged and no `Authorization` header is set.
class ApiKeysApi {
  ApiKeysApi(this._dio);
  final Dio _dio;

  String _base(String workspaceCode) =>
      '/v1/admin/workspaces/$workspaceCode/api-keys';

  Future<List<ApiKey>> list(String workspaceCode) async {
    try {
      final res = await _dio.get(_base(workspaceCode));
      return (res.data as List)
          .map((e) => ApiKey.fromJson(_asMap(e)))
          .toList();
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<ApiKey> get(String workspaceCode, String keyId) async {
    try {
      final res = await _dio.get('${_base(workspaceCode)}/$keyId');
      return ApiKey.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  /// Mint a key. The returned [ApiKeyCreated.secret] is shown once and then
  /// unrecoverable.
  Future<ApiKeyCreated> create(
    String workspaceCode, {
    required String name,
    required String role,
    String? expiresAt,
  }) async {
    try {
      final res = await _dio.post(
        _base(workspaceCode),
        data: ApiKeyCreateRequest(name: name, role: role, expiresAt: expiresAt)
            .toJson(),
      );
      return ApiKeyCreated.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<ApiKey> rename(
    String workspaceCode,
    String keyId, {
    required String name,
  }) async {
    try {
      final res = await _dio.patch('${_base(workspaceCode)}/$keyId',
          data: {'name': name});
      return ApiKey.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  /// Revoke (idempotent server-side). 204 on success.
  Future<void> revoke(String workspaceCode, String keyId) async {
    try {
      await _dio.delete('${_base(workspaceCode)}/$keyId');
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  Future<List<ApiKeyAuditEvent>> audit(
    String workspaceCode,
    String keyId, {
    int page = 0,
    int size = 50,
  }) async {
    try {
      final res = await _dio.get('${_base(workspaceCode)}/$keyId/audit',
          queryParameters: {'page': page, 'size': size});
      return (res.data as List)
          .map((e) => ApiKeyAuditEvent.fromJson(_asMap(e)))
          .toList();
    } on DioException catch (e) {
      throw _toException(e);
    }
  }

  ApiKeyApiException _toException(DioException e) {
    final data = e.response?.data;
    String? message;
    String? code;
    if (data is Map) {
      if (data['message'] is String) message = data['message'] as String;
      if (data['error'] is String) code = data['error'] as String;
    }
    return ApiKeyApiException(
      statusCode: e.response?.statusCode,
      message: message ?? 'Network error',
      errorCode: code,
    );
  }

  static Map<String, dynamic> _asMap(dynamic raw) =>
      Map<String, dynamic>.from(raw as Map);
}

class ApiKeyApiException implements Exception {
  ApiKeyApiException({
    required this.statusCode,
    required this.message,
    this.errorCode,
  });
  final int? statusCode;
  final String message;
  final String? errorCode;

  bool get isNotFound => statusCode == 404;

  /// The per-workspace active-key cap was hit (backend §14 D2).
  bool get isCapReached =>
      statusCode == 409 || errorCode == 'api_key_cap_reached';

  @override
  String toString() => 'ApiKeyApiException($statusCode, $errorCode): $message';
}

final apiKeysApiProvider = FutureProvider<ApiKeysApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return ApiKeysApi(dio);
});

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/api_client.dart';
import '../models/operator.dart';

/// Thin wrapper over /v1/auth/*. Cookies are handled by the browser; the
/// AuthApi never sees the YAYA_SESSION value (which is HttpOnly).
///
/// Errors are normalised into [AuthApiException] carrying the backend's
/// user-safe message ("Invalid username or password") so the login screen
/// can render it verbatim.
class AuthApi {
  AuthApi(this._dio);
  final Dio _dio;

  Future<Operator> login({required String username, required String password}) async {
    try {
      final res = await _dio.post('/v1/auth/login', data: {
        'username': username,
        'password': password,
      });
      return Operator.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  Future<Operator?> me() async {
    try {
      final res = await _dio.get('/v1/auth/me');
      return Operator.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) return null;
      throw _toAuthException(e);
    }
  }

  Future<void> logout() async {
    try {
      await _dio.post('/v1/auth/logout');
    } on DioException catch (e) {
      // Idempotent — even a 401 from logout means the session is already gone.
      if (e.response?.statusCode == 401) return;
      throw _toAuthException(e);
    }
  }

  AuthApiException _toAuthException(DioException e) {
    final data = e.response?.data;
    String? message;
    if (data is Map && data['message'] is String) message = data['message'] as String;
    return AuthApiException(
      statusCode: e.response?.statusCode,
      message: message ?? 'Network error',
    );
  }

  static Map<String, dynamic> _asMap(dynamic raw) => Map<String, dynamic>.from(raw as Map);
}

class AuthApiException implements Exception {
  AuthApiException({required this.statusCode, required this.message});
  final int? statusCode;
  final String message;

  @override
  String toString() => 'AuthApiException($statusCode): $message';
}

final authApiProvider = FutureProvider<AuthApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return AuthApi(dio);
});

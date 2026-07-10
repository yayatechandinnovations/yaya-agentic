import 'package:dio/dio.dart';

class UserRemoteDataSource {
  UserRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> members(String org) async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs/$org/members');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<void> lock(String userId) =>
      _dio.post<Map<String, dynamic>>('/v1/identity/admin/users/$userId/lock');

  Future<void> disable(String userId) =>
      _dio.post<Map<String, dynamic>>('/v1/identity/admin/users/$userId/disable');

  Future<void> delete(String userId) =>
      _dio.delete<Map<String, dynamic>>('/v1/identity/admin/users/$userId');

  Future<String> invite(String org, String email, String? role) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org/invitations',
      data: {'email': email, if (role != null && role.isNotEmpty) 'role': role},
    );
    return res.data?['token'] as String? ?? '';
  }
}

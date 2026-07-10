import 'package:dio/dio.dart';

class GroupRemoteDataSource {
  GroupRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list(String org) async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs/$org/groups');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(String org, String name) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org/groups',
      data: {'name': name},
    );
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> members(String groupId) async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/groups/$groupId/members');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<void> addMember(String groupId, String userId) =>
      _dio.put<Map<String, dynamic>>('/v1/identity/admin/groups/$groupId/members/$userId');

  Future<void> removeMember(String groupId, String userId) =>
      _dio.delete<Map<String, dynamic>>('/v1/identity/admin/groups/$groupId/members/$userId');
}

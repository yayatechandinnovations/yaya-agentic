import 'package:dio/dio.dart';

import '../../domain/entities/role_assignment_request.dart';

class RoleRemoteDataSource {
  RoleRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list(String org) async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs/$org/roles');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<List<String>> permissionCatalog() async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/permissions');
    return (res.data ?? []).cast<String>();
  }

  Future<Map<String, dynamic>> create(String org, String name, List<String> permissions) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org/roles',
      data: {'name': name, 'permissions': permissions},
    );
    return res.data ?? const {};
  }

  Future<void> assign(RoleAssignmentRequest r) async {
    await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/${r.org}/role-assignments',
      data: {
        'subjectKind': r.subjectKind,
        'subjectId': r.subjectId,
        'roleId': r.roleId,
        if (r.scopeType != null && r.scopeType!.isNotEmpty) 'scopeType': r.scopeType,
        if (r.scopeId != null && r.scopeId!.isNotEmpty) 'scopeId': r.scopeId,
      },
    );
  }
}

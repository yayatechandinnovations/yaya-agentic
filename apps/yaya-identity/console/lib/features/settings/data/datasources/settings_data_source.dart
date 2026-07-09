import 'package:dio/dio.dart';

class SettingsDataSource {
  SettingsDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> getOrg(String org) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/identity/admin/orgs/$org');
    return res.data ?? const {};
  }

  Future<void> setMfa(String org, bool required) =>
      _dio.put<Map<String, dynamic>>('/v1/identity/admin/orgs/$org/mfa', data: {'required': required});

  Future<String> rotateKeys() async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/identity/admin/keys/rotate');
    return res.data?['activeKid'] as String? ?? '';
  }
}

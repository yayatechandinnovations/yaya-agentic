import 'package:dio/dio.dart';

class ClientRemoteDataSource {
  ClientRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list() async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/clients');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<void> register(
      String clientId, String secret, List<String> audiences, List<String> scopes) async {
    await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/clients',
      data: {'clientId': clientId, 'secret': secret, 'audiences': audiences, 'scopes': scopes},
    );
  }

  Future<void> rotate(String clientId, String secret) async {
    await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/clients/$clientId/rotate-secret',
      data: {'secret': secret},
    );
  }
}

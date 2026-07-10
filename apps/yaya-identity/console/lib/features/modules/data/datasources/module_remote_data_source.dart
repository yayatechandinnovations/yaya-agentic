import 'package:dio/dio.dart';

class ModuleRemoteDataSource {
  ModuleRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<String>> catalog() async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/permissions');
    return (res.data ?? []).cast<String>();
  }

  Future<void> declare(String app, List<String> keys) =>
      _dio.post<List<dynamic>>('/v1/identity/apps/$app/permissions', data: keys);
}

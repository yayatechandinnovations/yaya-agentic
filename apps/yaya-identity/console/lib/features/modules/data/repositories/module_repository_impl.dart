import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/repositories/module_repository.dart';
import '../datasources/module_remote_data_source.dart';

class ModuleRepositoryImpl implements ModuleRepository {
  ModuleRepositoryImpl(this._remote);

  final ModuleRemoteDataSource _remote;

  @override
  Future<List<String>> catalog() => _guard(() => _remote.catalog());

  @override
  Future<void> declare(String app, List<String> keys) =>
      _guard(() => _remote.declare(app, keys));

  Future<T> _guard<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw const AuthFailure('session expired — please sign in again');
      }
      final serverMessage = switch (e.response?.data) {
        {'error': final String m} => m,
        {'message': final String m} => m,
        _ => null,
      };
      throw ServerFailure(
          serverMessage ?? 'request failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

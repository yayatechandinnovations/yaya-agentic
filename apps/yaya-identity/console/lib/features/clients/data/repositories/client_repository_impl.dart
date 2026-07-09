import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/client.dart';
import '../../domain/repositories/client_repository.dart';
import '../datasources/client_remote_data_source.dart';
import '../models/client_model.dart';

class ClientRepositoryImpl implements ClientRepository {
  ClientRepositoryImpl(this._remote);

  final ClientRemoteDataSource _remote;

  @override
  Future<List<Client>> list() =>
      _guard(() async => (await _remote.list()).map(ClientModel.fromJson).toList());

  @override
  Future<void> registerServiceAccount(
          String clientId, String secret, List<String> audiences, List<String> scopes) =>
      _guard(() => _remote.register(clientId, secret, audiences, scopes));

  @override
  Future<void> rotateSecret(String clientId, String secret) =>
      _guard(() => _remote.rotate(clientId, secret));

  Future<T> _guard<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw const AuthFailure('session expired — please sign in again');
      }
      throw ServerFailure('request failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

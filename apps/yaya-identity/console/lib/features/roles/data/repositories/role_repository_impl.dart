import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/role.dart';
import '../../domain/entities/role_assignment_request.dart';
import '../../domain/repositories/role_repository.dart';
import '../datasources/role_remote_data_source.dart';
import '../models/role_model.dart';

class RoleRepositoryImpl implements RoleRepository {
  RoleRepositoryImpl(this._remote);

  final RoleRemoteDataSource _remote;

  @override
  Future<List<Role>> list(String org) => _guard(() async =>
      (await _remote.list(org)).map(RoleModel.fromJson).toList());

  @override
  Future<Role> create(String org, String name, List<String> permissions) =>
      _guard(() async => RoleModel.fromJson(await _remote.create(org, name, permissions)));

  @override
  Future<void> assign(RoleAssignmentRequest request) => _guard(() => _remote.assign(request));

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

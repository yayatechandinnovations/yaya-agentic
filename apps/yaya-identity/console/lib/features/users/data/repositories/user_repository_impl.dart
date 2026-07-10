import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/member.dart';
import '../../domain/repositories/user_repository.dart';
import '../datasources/user_remote_data_source.dart';
import '../models/member_model.dart';

class UserRepositoryImpl implements UserRepository {
  UserRepositoryImpl(this._remote);

  final UserRemoteDataSource _remote;

  @override
  Future<List<Member>> members(String org) =>
      _guard(() async => (await _remote.members(org)).map(MemberModel.fromJson).toList());

  @override
  Future<void> lock(String userId) => _guard(() => _remote.lock(userId));

  @override
  Future<void> disable(String userId) => _guard(() => _remote.disable(userId));

  @override
  Future<void> delete(String userId) => _guard(() => _remote.delete(userId));

  @override
  Future<String> invite(String org, String email, String? role) =>
      _guard(() => _remote.invite(org, email, role));

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

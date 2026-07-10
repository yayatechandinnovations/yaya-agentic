import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../../users/data/models/member_model.dart';
import '../../../users/domain/entities/member.dart';
import '../../domain/entities/group.dart';
import '../../domain/repositories/group_repository.dart';
import '../datasources/group_remote_data_source.dart';
import '../models/group_model.dart';

class GroupRepositoryImpl implements GroupRepository {
  GroupRepositoryImpl(this._remote);

  final GroupRemoteDataSource _remote;

  @override
  Future<List<Group>> list(String org) =>
      _guard(() async => (await _remote.list(org)).map(GroupModel.fromJson).toList());

  @override
  Future<Group> create(String org, String name) =>
      _guard(() async => GroupModel.fromJson(await _remote.create(org, name)));

  @override
  Future<List<Member>> members(String groupId) =>
      _guard(() async => (await _remote.members(groupId)).map(MemberModel.fromJson).toList());

  @override
  Future<void> addMember(String groupId, String userId) =>
      _guard(() => _remote.addMember(groupId, userId));

  @override
  Future<void> removeMember(String groupId, String userId) =>
      _guard(() => _remote.removeMember(groupId, userId));

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

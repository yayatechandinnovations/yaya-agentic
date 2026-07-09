import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/organization.dart';
import '../../domain/repositories/org_repository.dart';
import '../datasources/org_remote_data_source.dart';
import '../models/organization_model.dart';

class OrgRepositoryImpl implements OrgRepository {
  OrgRepositoryImpl(this._remote);

  final OrgRemoteDataSource _remote;

  @override
  Future<List<Organization>> list() async {
    try {
      final rows = await _remote.list();
      return rows.map(OrganizationModel.fromJson).toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  @override
  Future<Organization> create(String name) async {
    try {
      return OrganizationModel.fromJson(await _remote.create(name));
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Failure _mapError(DioException e) {
    if (e.response?.statusCode == 401) {
      return const AuthFailure('session expired — please sign in again');
    }
    if (e.type == DioExceptionType.connectionError) {
      return const NetworkFailure('cannot reach yaya-identity');
    }
    return ServerFailure('request failed: ${e.response?.statusCode ?? e.message}');
  }
}

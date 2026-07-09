import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/audit_row.dart';
import '../../domain/repositories/audit_repository.dart';
import '../datasources/audit_remote_data_source.dart';
import '../models/audit_row_model.dart';

class AuditRepositoryImpl implements AuditRepository {
  AuditRepositoryImpl(this._remote);

  final AuditRemoteDataSource _remote;

  @override
  Future<List<AuditRow>> read(String org, {int limit = 50}) async {
    try {
      final rows = await _remote.read(org, limit);
      return rows.map(AuditRowModel.fromJson).toList();
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw const AuthFailure('session expired — please sign in again');
      }
      throw ServerFailure('audit read failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

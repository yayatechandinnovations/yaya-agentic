import 'dart:convert';

import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/policy.dart';
import '../../domain/entities/policy_draft.dart';
import '../../domain/repositories/policy_admin_repository.dart';
import '../datasources/policy_admin_data_source.dart';
import '../models/policy_model.dart';

class PolicyAdminRepositoryImpl implements PolicyAdminRepository {
  PolicyAdminRepositoryImpl(this._remote);

  final PolicyAdminDataSource _remote;

  @override
  Future<List<Policy>> list(String org) async {
    try {
      return (await _remote.list(org)).map(PolicyModel.fromJson).toList();
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  @override
  Future<Policy> create(PolicyDraft draft) async {
    final dynamic target;
    final dynamic condition;
    try {
      target = jsonDecode(draft.targetJson);
      condition = (draft.conditionJson == null || draft.conditionJson!.trim().isEmpty)
          ? null
          : jsonDecode(draft.conditionJson!);
    } on FormatException {
      throw const UnexpectedFailure('target/condition must be valid JSON');
    }
    try {
      final body = <String, dynamic>{
        'name': draft.name,
        'effect': draft.effect,
        'target': target,
        'condition': condition,
        'priority': draft.priority,
        'userReason': draft.userReason,
        'auditReason': draft.auditReason,
      };
      return PolicyModel.fromJson(await _remote.create(draft.org, body));
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Failure _mapError(DioException e) {
    if (e.response?.statusCode == 401) {
      return const AuthFailure('session expired — please sign in again');
    }
    // A 400 here is usually the backend's #3 guard (DENY needs two distinct reasons).
    if (e.response?.statusCode == 400) {
      return const ServerFailure('rejected: a DENY policy needs two distinct reasons, and JSON must be valid');
    }
    return ServerFailure('request failed: ${e.response?.statusCode ?? e.message}');
  }
}

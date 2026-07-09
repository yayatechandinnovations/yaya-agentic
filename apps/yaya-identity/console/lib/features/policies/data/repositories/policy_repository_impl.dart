import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/decision.dart';
import '../../domain/entities/decision_query.dart';
import '../../domain/repositories/policy_repository.dart';
import '../datasources/policy_remote_data_source.dart';
import '../models/decision_model.dart';

class PolicyRepositoryImpl implements PolicyRepository {
  PolicyRepositoryImpl(this._remote);

  final PolicyRemoteDataSource _remote;

  @override
  Future<Decision> simulate(DecisionQuery query) async {
    try {
      return DecisionModel.fromJson(await _remote.simulate(query));
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw const AuthFailure('session expired — please sign in again');
      }
      throw ServerFailure('simulate failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

import 'package:dio/dio.dart';

import '../../domain/entities/decision_query.dart';

class PolicyRemoteDataSource {
  PolicyRemoteDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> simulate(DecisionQuery q) async {
    final resource = <String, dynamic>{};
    if (q.resourceType != null && q.resourceType!.isNotEmpty) {
      resource['type'] = q.resourceType;
      resource['id'] = q.resourceId;
    }
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/${q.org}/policies/simulate',
      data: {
        'org': q.org,
        'subject': q.subject,
        'action': q.action,
        'resource': resource,
      },
    );
    return res.data ?? const {};
  }
}

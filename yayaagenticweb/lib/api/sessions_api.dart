import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/start_session.dart';
import 'api_client.dart';
import 'sse_client.dart';

class SessionsApi {
  SessionsApi(this._dio);
  final Dio _dio;

  Future<StartSessionResponse> start(StartSessionRequest req) async {
    final res = await _dio.post('/v1/sessions', data: req.toJson());
    return StartSessionResponse.fromJson(Map<String, dynamic>.from(res.data));
  }

  SseClient sse() => SseClient(_dio);

  Future<void> end(String sessionId) async {
    await _dio.post('/v1/sessions/$sessionId/end');
  }
}

final sessionsApiProvider = FutureProvider<SessionsApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return SessionsApi(dio);
});

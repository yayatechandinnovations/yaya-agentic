import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'api_config.dart';

/// Dio HTTP client wired with base URL and auth/trace interceptor stubs.
/// Real auth lands in M1; for M0 the interceptor is a no-op placeholder.
final dioProvider = FutureProvider<Dio>((ref) async {
  final config = await ref.watch(apiConfigProvider.future);
  final dio = Dio(BaseOptions(
    baseUrl: config.baseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 30),
    headers: {'Accept': 'application/json'},
  ));

  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) {
      // Operator JWT lands in F1.1. M0 sends no auth header.
      handler.next(options);
    },
    onError: (e, handler) {
      handler.next(e);
    },
  ));

  return dio;
});

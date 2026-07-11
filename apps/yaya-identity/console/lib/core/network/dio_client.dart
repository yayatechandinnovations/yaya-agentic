import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../storage/token_store.dart';
import 'auth_interceptor.dart';
import 'base_path_interceptor.dart';

/// Builds the shared [Dio] against the identity API origin, with the base-path
/// and bearer-token interceptors wired in.
class DioClient {
  static Dio build(AppConfig config, TokenStore tokens,
      {void Function()? onUnauthorized}) {
    final dio = Dio(
      BaseOptions(
        baseUrl: config.apiOrigin,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 15),
        headers: {'Accept': 'application/json'},
      ),
    );
    dio.interceptors.add(BasePathInterceptor(config.basePath));
    dio.interceptors.add(AuthInterceptor(tokens, onUnauthorized: onUnauthorized));
    return dio;
  }
}

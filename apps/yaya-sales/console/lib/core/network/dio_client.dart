import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../storage/store_context.dart';
import '../storage/token_store.dart';
import 'auth_interceptor.dart';
import 'base_path_interceptor.dart';
import 'store_interceptor.dart';

/// Builds the two HTTP clients: one for the yaya-sales admin API (bearer +
/// store header) and one for the yaya-identity token endpoint (no auth — the
/// PKCE verifier authenticates the exchange).
class DioClient {
  static Dio sales(AppConfig config, TokenStore tokens, StoreContext stores,
      {void Function()? onUnauthorized}) {
    final dio = Dio(
      BaseOptions(
        baseUrl: config.salesOrigin,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 20),
        headers: {'Accept': 'application/json'},
      ),
    );
    dio.interceptors.add(BasePathInterceptor(config.salesBasePath));
    dio.interceptors.add(AuthInterceptor(tokens, onUnauthorized: onUnauthorized));
    dio.interceptors.add(StoreInterceptor(stores));
    return dio;
  }

  static Dio identity(AppConfig config) {
    final dio = Dio(
      BaseOptions(
        baseUrl: config.identityOrigin,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 15),
        headers: {'Accept': 'application/json'},
      ),
    );
    dio.interceptors.add(BasePathInterceptor(config.identityBasePath));
    return dio;
  }
}

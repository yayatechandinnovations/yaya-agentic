import 'package:dio/dio.dart';

/// Prepends the service's base path (e.g. `/identity`) to every request path, so
/// the console works whether the API is served at the root of its origin or
/// under a subpath. Centralised here so datasources keep using plain
/// leading-slash paths ("/v1/…", "/oauth2/token").
class BasePathInterceptor extends Interceptor {
  BasePathInterceptor(this.basePath);

  final String basePath;

  /// Pure path rewrite — unit-testable without a Dio handler.
  static String apply(String path, String basePath) {
    if (basePath.isEmpty) return path;
    if (!path.startsWith('/')) return path;                 // absolute URL: leave it
    if (path == basePath || path.startsWith('$basePath/')) return path; // already prefixed
    return '$basePath$path';
  }

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.path = apply(options.path, basePath);
    handler.next(options);
  }
}

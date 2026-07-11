import 'package:dio/dio.dart';

import '../storage/token_store.dart';

/// Attaches the operator's bearer token to every admin request, and reports a
/// 401 (stale/expired token) so the session can be torn down centrally. 403 is
/// NOT reported — that's a live session lacking permission, not a dead one.
class AuthInterceptor extends Interceptor {
  AuthInterceptor(this._tokens, {this.onUnauthorized});

  final TokenStore _tokens;
  final void Function()? onUnauthorized;

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final token = await _tokens.readToken();
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.response?.statusCode == 401) {
      onUnauthorized?.call();
    }
    handler.next(err);
  }
}

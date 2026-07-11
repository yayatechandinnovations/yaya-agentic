import 'package:dio/dio.dart';

import '../storage/token_store.dart';

/// Attaches the bearer token to admin/PDP requests and surfaces a 401 as a
/// clear signal the session expired (403 is a live session lacking permission
/// — not reported).
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

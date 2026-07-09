import 'package:dio/dio.dart';

import '../storage/token_store.dart';

/// Attaches the bearer token to admin/PDP requests and surfaces a 401 as a
/// clear signal the session expired.
class AuthInterceptor extends Interceptor {
  AuthInterceptor(this._tokens);

  final TokenStore _tokens;

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final token = await _tokens.readToken();
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }
}

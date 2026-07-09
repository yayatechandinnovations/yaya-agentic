import 'package:dio/dio.dart';

import '../storage/token_store.dart';

/// Attaches the operator's bearer token to every admin request.
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

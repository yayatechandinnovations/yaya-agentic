import 'package:dio/dio.dart';

import '../../../../core/config/app_config.dart';

/// Talks to the OAuth token endpoint. Public client → no secret; the PKCE
/// verifier authenticates the exchange.
class AuthRemoteDataSource {
  AuthRemoteDataSource(this._dio, this._config);

  final Dio _dio;
  final AppConfig _config;

  Future<String> exchangeCode(String code, String verifier) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/oauth2/token',
      data: {
        'grant_type': 'authorization_code',
        'code': code,
        'redirect_uri': _config.redirectUri,
        'client_id': _config.clientId,
        'code_verifier': verifier,
      },
      options: Options(
        contentType: Headers.formUrlEncodedContentType,
      ),
    );
    final token = response.data?['access_token'] as String?;
    if (token == null || token.isEmpty) {
      throw const FormatException('token response missing access_token');
    }
    return token;
  }
}

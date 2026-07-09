import 'package:dio/dio.dart';

import '../../../../core/config/app_config.dart';
import '../../../../core/error/failure.dart';
import '../../../../core/storage/token_store.dart';
import '../../domain/entities/auth_session.dart';
import '../../domain/repositories/auth_repository.dart';
import '../datasources/auth_remote_data_source.dart';
import '../pkce.dart';
import '../platform/oauth_launcher.dart';

class AuthRepositoryImpl implements AuthRepository {
  AuthRepositoryImpl({
    required AuthRemoteDataSource remote,
    required TokenStore tokens,
    required OAuthLauncher launcher,
    required AppConfig config,
  })  : _remote = remote,
        _tokens = tokens,
        _launcher = launcher,
        _config = config;

  final AuthRemoteDataSource _remote;
  final TokenStore _tokens;
  final OAuthLauncher _launcher;
  final AppConfig _config;

  @override
  Future<void> beginLogin() async {
    final pkce = Pkce.generate();
    await _tokens.writePkceVerifier(pkce.verifier);
    final url = Uri.parse('${_config.issuerBaseUrl}/oauth2/authorize').replace(
      queryParameters: {
        'response_type': 'code',
        'client_id': _config.clientId,
        'scope': 'openid profile',
        'redirect_uri': _config.redirectUri,
        'code_challenge': pkce.challenge,
        'code_challenge_method': 'S256',
      },
    ).toString();
    _launcher.redirect(url);
  }

  @override
  Future<AuthSession> completeLogin(String code) async {
    final verifier = await _tokens.readPkceVerifier();
    if (verifier == null) {
      throw const AuthFailure('login state was lost; please try again');
    }
    try {
      final token = await _remote.exchangeCode(code, verifier);
      await _tokens.writeToken(token);
      return AuthSession(accessToken: token);
    } on DioException catch (e) {
      throw AuthFailure('token exchange failed: ${e.response?.statusCode ?? e.message}');
    }
  }

  @override
  Future<AuthSession?> currentSession() async {
    final token = await _tokens.readToken();
    return (token == null || token.isEmpty) ? null : AuthSession(accessToken: token);
  }

  @override
  Future<void> logout() => _tokens.clear();
}

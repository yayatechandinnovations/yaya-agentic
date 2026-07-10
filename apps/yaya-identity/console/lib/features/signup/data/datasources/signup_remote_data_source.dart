import 'package:dio/dio.dart';

/// The public onboarding endpoints — no bearer token exists yet by definition.
class SignupRemoteDataSource {
  SignupRemoteDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> register({
    required String orgName,
    required String displayName,
    required String email,
    required String phone,
    required String password,
  }) async {
    final res = await _dio.post<Map<String, dynamic>>('/signup/register', data: {
      'orgName': orgName,
      'displayName': displayName,
      'email': email,
      'phone': phone,
      'password': password,
    });
    return res.data ?? {};
  }

  Future<Map<String, dynamic>> verify(String signupId, String code) async {
    final res = await _dio.post<Map<String, dynamic>>('/signup/verify', data: {
      'signupId': signupId,
      'code': code,
    });
    return res.data ?? {};
  }

  Future<Map<String, dynamic>> resend(String signupId) async {
    final res = await _dio.post<Map<String, dynamic>>('/signup/resend', data: {
      'signupId': signupId,
    });
    return res.data ?? {};
  }

  Future<Map<String, dynamic>> acceptInvitation({
    required String token,
    required String displayName,
    required String password,
  }) async {
    final res = await _dio.post<Map<String, dynamic>>('/signup/invitations/accept', data: {
      'token': token,
      'displayName': displayName,
      'password': password,
    });
    return res.data ?? {};
  }
}

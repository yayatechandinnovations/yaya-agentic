import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/signup.dart';
import '../../domain/repositories/signup_repository.dart';
import '../datasources/signup_remote_data_source.dart';

class SignupRepositoryImpl implements SignupRepository {
  SignupRepositoryImpl(this._remote);

  final SignupRemoteDataSource _remote;

  @override
  Future<RegisteredSignup> register({
    required String orgName,
    required String displayName,
    required String email,
    required String phone,
    required String password,
  }) =>
      _guard(() async {
        final json = await _remote.register(
          orgName: orgName,
          displayName: displayName,
          email: email,
          phone: phone,
          password: password,
        );
        return RegisteredSignup(
          signupId: json['signupId'] as String,
          devOtp: json['devOtp'] as String?,
        );
      });

  @override
  Future<VerifiedSignup> verify(String signupId, String code) => _guard(() async {
        final json = await _remote.verify(signupId, code);
        return VerifiedSignup(
          userId: json['userId'] as String,
          orgId: json['orgId'] as String,
          orgName: json['orgName'] as String,
        );
      });

  @override
  Future<RegisteredSignup> resend(String signupId) => _guard(() async {
        final json = await _remote.resend(signupId);
        return RegisteredSignup(
          signupId: json['signupId'] as String,
          devOtp: json['devOtp'] as String?,
        );
      });

  @override
  Future<AcceptedInvitation> acceptInvitation({
    required String token,
    required String displayName,
    required String password,
  }) =>
      _guard(() async {
        final json = await _remote.acceptInvitation(
          token: token,
          displayName: displayName,
          password: password,
        );
        return AcceptedInvitation(
          userId: json['userId'] as String,
          orgId: json['orgId'] as String,
        );
      });

  /// The signup endpoints answer with meaningful `error` messages (weak
  /// password, phone in use, cooldown…) — surface those verbatim.
  Future<T> _guard<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on DioException catch (e) {
      final data = e.response?.data;
      if (data is Map && data['error'] is String) {
        throw ServerFailure(data['error'] as String);
      }
      throw ServerFailure('request failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

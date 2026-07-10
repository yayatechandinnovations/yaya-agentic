import '../entities/signup.dart';

abstract interface class SignupRepository {
  Future<RegisteredSignup> register({
    required String orgName,
    required String displayName,
    required String email,
    required String phone,
    required String password,
  });

  Future<VerifiedSignup> verify(String signupId, String code);

  Future<RegisteredSignup> resend(String signupId);

  Future<AcceptedInvitation> acceptInvitation({
    required String token,
    required String displayName,
    required String password,
  });
}

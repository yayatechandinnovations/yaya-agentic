import '../entities/signup.dart';
import '../repositories/signup_repository.dart';

class RegisterSignup {
  const RegisterSignup(this._repo);
  final SignupRepository _repo;
  Future<RegisteredSignup> call({
    required String orgName,
    required String displayName,
    required String email,
    required String phone,
    required String password,
  }) =>
      _repo.register(
        orgName: orgName,
        displayName: displayName,
        email: email,
        phone: phone,
        password: password,
      );
}

class VerifySignup {
  const VerifySignup(this._repo);
  final SignupRepository _repo;
  Future<VerifiedSignup> call(String signupId, String code) => _repo.verify(signupId, code);
}

class ResendSignupOtp {
  const ResendSignupOtp(this._repo);
  final SignupRepository _repo;
  Future<RegisteredSignup> call(String signupId) => _repo.resend(signupId);
}

class AcceptInvitation {
  const AcceptInvitation(this._repo);
  final SignupRepository _repo;
  Future<AcceptedInvitation> call({
    required String token,
    required String displayName,
    required String password,
  }) =>
      _repo.acceptInvitation(token: token, displayName: displayName, password: password);
}

part of 'signup_bloc.dart';

enum SignupStatus { form, submitting, codeSent, verifying, completed }

class SignupState extends Equatable {
  const SignupState({
    this.status = SignupStatus.form,
    this.signupId,
    this.phone,
    this.devOtp,
    this.verified,
    this.error,
    this.message,
  });

  final SignupStatus status;
  final String? signupId;
  final String? phone;

  /// Present only when the server echoes locally-generated OTPs (dev installs).
  final String? devOtp;
  final VerifiedSignup? verified;
  final String? error;
  final String? message;

  SignupState copyWith({
    SignupStatus? status,
    String? signupId,
    String? phone,
    String? devOtp,
    VerifiedSignup? verified,
    Object? error = _unset,
    Object? message = _unset,
  }) {
    return SignupState(
      status: status ?? this.status,
      signupId: signupId ?? this.signupId,
      phone: phone ?? this.phone,
      devOtp: devOtp ?? this.devOtp,
      verified: verified ?? this.verified,
      error: error == _unset ? this.error : error as String?,
      message: message == _unset ? null : message as String?,
    );
  }

  static const _unset = Object();

  @override
  List<Object?> get props => [status, signupId, phone, devOtp, verified, error, message];
}

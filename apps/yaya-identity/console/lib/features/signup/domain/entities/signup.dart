import 'package:equatable/equatable.dart';

/// A registration awaiting phone verification. [devOtp] is present only when
/// the server runs with the dev-only echo flag — never in production.
class RegisteredSignup extends Equatable {
  const RegisteredSignup({required this.signupId, this.devOtp});

  final String signupId;
  final String? devOtp;

  @override
  List<Object?> get props => [signupId, devOtp];
}

class VerifiedSignup extends Equatable {
  const VerifiedSignup({required this.userId, required this.orgId, required this.orgName});

  final String userId;
  final String orgId;
  final String orgName;

  @override
  List<Object?> get props => [userId, orgId, orgName];
}

class AcceptedInvitation extends Equatable {
  const AcceptedInvitation({required this.userId, required this.orgId});

  final String userId;
  final String orgId;

  @override
  List<Object?> get props => [userId, orgId];
}

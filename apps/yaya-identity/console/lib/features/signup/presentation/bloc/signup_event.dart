part of 'signup_bloc.dart';

sealed class SignupEvent extends Equatable {
  const SignupEvent();

  @override
  List<Object?> get props => [];
}

class SignupSubmitted extends SignupEvent {
  const SignupSubmitted({
    required this.orgName,
    required this.displayName,
    required this.email,
    required this.phone,
    required this.password,
  });

  final String orgName;
  final String displayName;
  final String email;
  final String phone;
  final String password;

  @override
  List<Object?> get props => [orgName, displayName, email, phone, password];
}

class SignupCodeSubmitted extends SignupEvent {
  const SignupCodeSubmitted(this.code);

  final String code;

  @override
  List<Object?> get props => [code];
}

class SignupResendRequested extends SignupEvent {
  const SignupResendRequested();
}

class SignupRestarted extends SignupEvent {
  const SignupRestarted();
}

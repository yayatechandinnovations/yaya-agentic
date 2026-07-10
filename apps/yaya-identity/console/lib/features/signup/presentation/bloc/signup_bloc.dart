import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/signup.dart';
import '../../domain/usecases/signup_usecases.dart';

part 'signup_event.dart';
part 'signup_state.dart';

class SignupBloc extends Bloc<SignupEvent, SignupState> {
  SignupBloc({
    required RegisterSignup registerSignup,
    required VerifySignup verifySignup,
    required ResendSignupOtp resendSignupOtp,
  })  : _register = registerSignup,
        _verify = verifySignup,
        _resend = resendSignupOtp,
        super(const SignupState()) {
    on<SignupSubmitted>(_onSubmitted);
    on<SignupCodeSubmitted>(_onCodeSubmitted);
    on<SignupResendRequested>(_onResendRequested);
    on<SignupRestarted>((_, emit) => emit(const SignupState()));
  }

  final RegisterSignup _register;
  final VerifySignup _verify;
  final ResendSignupOtp _resend;

  Future<void> _onSubmitted(SignupSubmitted event, Emitter<SignupState> emit) async {
    emit(state.copyWith(status: SignupStatus.submitting, error: null));
    try {
      final registered = await _register(
        orgName: event.orgName,
        displayName: event.displayName,
        email: event.email,
        phone: event.phone,
        password: event.password,
      );
      emit(state.copyWith(
        status: SignupStatus.codeSent,
        signupId: registered.signupId,
        phone: event.phone,
        devOtp: registered.devOtp,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: SignupStatus.form, error: f.message));
    }
  }

  Future<void> _onCodeSubmitted(SignupCodeSubmitted event, Emitter<SignupState> emit) async {
    final signupId = state.signupId;
    if (signupId == null) return;
    emit(state.copyWith(status: SignupStatus.verifying, error: null));
    try {
      final verified = await _verify(signupId, event.code);
      emit(state.copyWith(status: SignupStatus.completed, verified: verified));
    } on Failure catch (f) {
      emit(state.copyWith(status: SignupStatus.codeSent, error: f.message));
    }
  }

  Future<void> _onResendRequested(SignupResendRequested event, Emitter<SignupState> emit) async {
    final signupId = state.signupId;
    if (signupId == null) return;
    try {
      final resent = await _resend(signupId);
      emit(state.copyWith(devOtp: resent.devOtp, message: 'A new code was sent.', error: null));
    } on Failure catch (f) {
      emit(state.copyWith(error: f.message));
    }
  }
}

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/signup.dart';
import '../../domain/usecases/signup_usecases.dart';

part 'accept_invite_event.dart';
part 'accept_invite_state.dart';

class AcceptInviteBloc extends Bloc<AcceptInviteEvent, AcceptInviteState> {
  AcceptInviteBloc({required AcceptInvitation acceptInvitation})
      : _accept = acceptInvitation,
        super(const AcceptInviteState()) {
    on<AcceptInviteSubmitted>(_onSubmitted);
  }

  final AcceptInvitation _accept;

  Future<void> _onSubmitted(
      AcceptInviteSubmitted event, Emitter<AcceptInviteState> emit) async {
    emit(state.copyWith(status: AcceptInviteStatus.submitting, error: null));
    try {
      final accepted = await _accept(
        token: event.token,
        displayName: event.displayName,
        password: event.password,
      );
      emit(state.copyWith(status: AcceptInviteStatus.completed, accepted: accepted));
    } on Failure catch (f) {
      emit(state.copyWith(status: AcceptInviteStatus.form, error: f.message));
    }
  }
}

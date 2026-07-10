part of 'accept_invite_bloc.dart';

enum AcceptInviteStatus { form, submitting, completed }

class AcceptInviteState extends Equatable {
  const AcceptInviteState({
    this.status = AcceptInviteStatus.form,
    this.accepted,
    this.error,
  });

  final AcceptInviteStatus status;
  final AcceptedInvitation? accepted;
  final String? error;

  AcceptInviteState copyWith({
    AcceptInviteStatus? status,
    AcceptedInvitation? accepted,
    Object? error = _unset,
  }) {
    return AcceptInviteState(
      status: status ?? this.status,
      accepted: accepted ?? this.accepted,
      error: error == _unset ? this.error : error as String?,
    );
  }

  static const _unset = Object();

  @override
  List<Object?> get props => [status, accepted, error];
}

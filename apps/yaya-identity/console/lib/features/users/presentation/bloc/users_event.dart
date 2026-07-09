part of 'users_bloc.dart';

sealed class UsersEvent extends Equatable {
  const UsersEvent();

  @override
  List<Object?> get props => [];
}

class MembersRequested extends UsersEvent {
  const MembersRequested(this.org);
  final String org;
  @override
  List<Object?> get props => [org];
}

class UserLockRequested extends UsersEvent {
  const UserLockRequested(this.org, this.userId);
  final String org;
  final String userId;
  @override
  List<Object?> get props => [org, userId];
}

class UserDisableRequested extends UsersEvent {
  const UserDisableRequested(this.org, this.userId);
  final String org;
  final String userId;
  @override
  List<Object?> get props => [org, userId];
}

class UserDeleteRequested extends UsersEvent {
  const UserDeleteRequested(this.org, this.userId);
  final String org;
  final String userId;
  @override
  List<Object?> get props => [org, userId];
}

class UserInviteRequested extends UsersEvent {
  const UserInviteRequested(this.org, this.email);
  final String org;
  final String email;
  @override
  List<Object?> get props => [org, email];
}

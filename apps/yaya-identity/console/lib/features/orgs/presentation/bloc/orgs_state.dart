part of 'orgs_bloc.dart';

enum OrgsStatus { initial, loading, loaded, failure }

class OrgsState extends Equatable {
  const OrgsState({
    this.status = OrgsStatus.initial,
    this.orgs = const [],
    this.error,
    this.message,
  });

  final OrgsStatus status;
  final List<Organization> orgs;
  final String? error;
  final String? message;

  OrgsState copyWith({
    OrgsStatus? status,
    List<Organization>? orgs,
    String? error,
    String? message,
  }) =>
      OrgsState(
        status: status ?? this.status,
        orgs: orgs ?? this.orgs,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, orgs, error, message];
}

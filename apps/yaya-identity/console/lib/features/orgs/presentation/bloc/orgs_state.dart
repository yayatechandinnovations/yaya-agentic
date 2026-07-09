part of 'orgs_bloc.dart';

enum OrgsStatus { initial, loading, loaded, failure }

class OrgsState extends Equatable {
  const OrgsState({
    this.status = OrgsStatus.initial,
    this.orgs = const [],
    this.error,
  });

  final OrgsStatus status;
  final List<Organization> orgs;
  final String? error;

  OrgsState copyWith({OrgsStatus? status, List<Organization>? orgs, String? error}) =>
      OrgsState(
        status: status ?? this.status,
        orgs: orgs ?? this.orgs,
        error: error,
      );

  @override
  List<Object?> get props => [status, orgs, error];
}

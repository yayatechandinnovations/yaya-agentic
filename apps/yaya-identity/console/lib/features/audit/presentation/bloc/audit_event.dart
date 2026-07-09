part of 'audit_bloc.dart';

sealed class AuditEvent extends Equatable {
  const AuditEvent();

  @override
  List<Object?> get props => [];
}

class AuditRequested extends AuditEvent {
  const AuditRequested(this.org);

  final String org;

  @override
  List<Object?> get props => [org];
}

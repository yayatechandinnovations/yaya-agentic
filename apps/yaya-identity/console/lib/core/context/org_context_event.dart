part of 'org_context_bloc.dart';

sealed class OrgContextEvent extends Equatable {
  const OrgContextEvent();

  @override
  List<Object?> get props => [];
}

class OrgContextRequested extends OrgContextEvent {
  const OrgContextRequested();
}

class OrgContextSelected extends OrgContextEvent {
  const OrgContextSelected(this.orgId);

  final String orgId;

  @override
  List<Object?> get props => [orgId];
}

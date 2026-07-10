part of 'orgs_bloc.dart';

sealed class OrgsEvent extends Equatable {
  const OrgsEvent();

  @override
  List<Object?> get props => [];
}

class OrgsRequested extends OrgsEvent {
  const OrgsRequested();
}

class OrgCreateRequested extends OrgsEvent {
  const OrgCreateRequested(this.draft);

  final OrgDraft draft;

  @override
  List<Object?> get props => [draft];
}

class OrgUpdateRequested extends OrgsEvent {
  const OrgUpdateRequested(this.orgId, this.draft);

  final String orgId;
  final OrgDraft draft;

  @override
  List<Object?> get props => [orgId, draft];
}

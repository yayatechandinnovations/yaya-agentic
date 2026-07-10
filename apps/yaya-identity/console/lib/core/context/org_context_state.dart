part of 'org_context_bloc.dart';

enum OrgContextStatus { initial, loading, loaded, failure }

class OrgContextState extends Equatable {
  const OrgContextState({
    this.status = OrgContextStatus.initial,
    this.orgs = const [],
    this.selectedOrgId,
    this.error,
  });

  final OrgContextStatus status;
  final List<Organization> orgs;
  final String? selectedOrgId;
  final String? error;

  Organization? get selectedOrg {
    for (final o in orgs) {
      if (o.id == selectedOrgId) return o;
    }
    return null;
  }

  OrgContextState copyWith({
    OrgContextStatus? status,
    List<Organization>? orgs,
    String? selectedOrgId,
    String? error,
  }) =>
      OrgContextState(
        status: status ?? this.status,
        orgs: orgs ?? this.orgs,
        selectedOrgId: selectedOrgId ?? this.selectedOrgId,
        error: error,
      );

  @override
  List<Object?> get props => [status, orgs, selectedOrgId, error];
}

part of 'policy_editor_bloc.dart';

sealed class PolicyEditorEvent extends Equatable {
  const PolicyEditorEvent();

  @override
  List<Object?> get props => [];
}

class PoliciesRequested extends PolicyEditorEvent {
  const PoliciesRequested(this.org);
  final String org;
  @override
  List<Object?> get props => [org];
}

class PolicyCreateRequested extends PolicyEditorEvent {
  const PolicyCreateRequested(this.draft);
  final PolicyDraft draft;
  @override
  List<Object?> get props => [draft];
}

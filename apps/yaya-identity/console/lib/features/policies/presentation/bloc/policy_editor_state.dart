part of 'policy_editor_bloc.dart';

enum PolicyEditorStatus { initial, loading, loaded, failure }

class PolicyEditorState extends Equatable {
  const PolicyEditorState({
    this.status = PolicyEditorStatus.initial,
    this.policies = const [],
    this.catalog = const [],
    this.org = '',
    this.error,
    this.message,
  });

  final PolicyEditorStatus status;
  final List<Policy> policies;
  final List<String> catalog;
  final String org;
  final String? error;
  final String? message;

  PolicyEditorState copyWith({
    PolicyEditorStatus? status,
    List<Policy>? policies,
    List<String>? catalog,
    String? org,
    String? error,
    String? message,
  }) =>
      PolicyEditorState(
        status: status ?? this.status,
        policies: policies ?? this.policies,
        catalog: catalog ?? this.catalog,
        org: org ?? this.org,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, policies, catalog, org, error, message];
}

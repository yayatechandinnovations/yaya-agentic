import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../roles/domain/usecases/role_usecases.dart';
import '../../domain/entities/policy.dart';
import '../../domain/entities/policy_draft.dart';
import '../../domain/usecases/policy_admin_usecases.dart';

part 'policy_editor_event.dart';
part 'policy_editor_state.dart';

class PolicyEditorBloc extends Bloc<PolicyEditorEvent, PolicyEditorState> {
  PolicyEditorBloc({
    required ListPolicies listPolicies,
    required CreatePolicy createPolicy,
    required GetPermissionCatalog getPermissionCatalog,
  })  : _listPolicies = listPolicies,
        _createPolicy = createPolicy,
        _getPermissionCatalog = getPermissionCatalog,
        super(const PolicyEditorState()) {
    on<PoliciesRequested>(_onRequested);
    on<PolicyCreateRequested>(_onCreate);
  }

  final ListPolicies _listPolicies;
  final CreatePolicy _createPolicy;
  final GetPermissionCatalog _getPermissionCatalog;

  Future<void> _onRequested(PoliciesRequested event, Emitter<PolicyEditorState> emit) async {
    emit(state.copyWith(status: PolicyEditorStatus.loading, org: event.org));
    try {
      // The declared actions power the target picker in the create dialog.
      final results = await Future.wait<Object>([
        _listPolicies(event.org),
        _getPermissionCatalog(),
      ]);
      emit(state.copyWith(
        status: PolicyEditorStatus.loaded,
        policies: results[0] as List<Policy>,
        catalog: (results[1] as List).cast<String>(),
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: PolicyEditorStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(PolicyCreateRequested event, Emitter<PolicyEditorState> emit) async {
    try {
      await _createPolicy(event.draft);
      emit(state.copyWith(message: 'Policy "${event.draft.name}" created.'));
      add(PoliciesRequested(event.draft.org));
    } on Failure catch (f) {
      emit(state.copyWith(status: PolicyEditorStatus.failure, error: f.message));
    }
  }
}

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/organization.dart';
import '../../domain/usecases/org_usecases.dart';

part 'orgs_event.dart';
part 'orgs_state.dart';

class OrgsBloc extends Bloc<OrgsEvent, OrgsState> {
  OrgsBloc({
    required ListOrgs listOrgs,
    required CreateOrg createOrg,
    required UpdateOrg updateOrg,
  })  : _listOrgs = listOrgs,
        _createOrg = createOrg,
        _updateOrg = updateOrg,
        super(const OrgsState()) {
    on<OrgsRequested>(_onRequested);
    on<OrgCreateRequested>(_onCreateRequested);
    on<OrgUpdateRequested>(_onUpdateRequested);
  }

  final ListOrgs _listOrgs;
  final CreateOrg _createOrg;
  final UpdateOrg _updateOrg;

  Future<void> _onRequested(OrgsRequested event, Emitter<OrgsState> emit) async {
    emit(state.copyWith(status: OrgsStatus.loading));
    try {
      final orgs = await _listOrgs();
      emit(state.copyWith(status: OrgsStatus.loaded, orgs: orgs));
    } on Failure catch (f) {
      emit(state.copyWith(status: OrgsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreateRequested(OrgCreateRequested event, Emitter<OrgsState> emit) async {
    try {
      await _createOrg(event.draft);
      emit(state.copyWith(message: 'Organization "${event.draft.name}" created.'));
      add(const OrgsRequested());   // refresh the list after a create
    } on Failure catch (f) {
      emit(state.copyWith(status: OrgsStatus.failure, error: f.message));
    }
  }

  Future<void> _onUpdateRequested(OrgUpdateRequested event, Emitter<OrgsState> emit) async {
    try {
      await _updateOrg(event.orgId, event.draft);
      emit(state.copyWith(message: 'Organization updated.'));
      add(const OrgsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: OrgsStatus.failure, error: f.message));
    }
  }
}

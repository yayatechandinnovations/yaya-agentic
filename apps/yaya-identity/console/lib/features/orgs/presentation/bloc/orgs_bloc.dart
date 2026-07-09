import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/organization.dart';
import '../../domain/usecases/org_usecases.dart';

part 'orgs_event.dart';
part 'orgs_state.dart';

class OrgsBloc extends Bloc<OrgsEvent, OrgsState> {
  OrgsBloc({required ListOrgs listOrgs, required CreateOrg createOrg})
      : _listOrgs = listOrgs,
        _createOrg = createOrg,
        super(const OrgsState()) {
    on<OrgsRequested>(_onRequested);
    on<OrgCreateRequested>(_onCreateRequested);
  }

  final ListOrgs _listOrgs;
  final CreateOrg _createOrg;

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
      await _createOrg(event.name);
      add(const OrgsRequested());   // refresh the list after a create
    } on Failure catch (f) {
      emit(state.copyWith(status: OrgsStatus.failure, error: f.message));
    }
  }
}

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/orgs/domain/entities/organization.dart';
import '../../features/orgs/domain/usecases/org_usecases.dart';
import '../error/failure.dart';

part 'org_context_event.dart';
part 'org_context_state.dart';

/// The console-wide organization scope — pick an org once (shell header) and
/// every org-scoped page follows it, instead of each page asking for an org id.
/// The selection survives reloads via [SharedPreferences].
class OrgContextBloc extends Bloc<OrgContextEvent, OrgContextState> {
  OrgContextBloc({required ListOrgs listOrgs, required SharedPreferences prefs})
      : _listOrgs = listOrgs,
        _prefs = prefs,
        super(const OrgContextState()) {
    on<OrgContextRequested>(_onRequested);
    on<OrgContextSelected>(_onSelected);
  }

  static const _prefsKey = 'org_scope';

  final ListOrgs _listOrgs;
  final SharedPreferences _prefs;

  Future<void> _onRequested(OrgContextRequested event, Emitter<OrgContextState> emit) async {
    emit(state.copyWith(status: OrgContextStatus.loading));
    try {
      final orgs = await _listOrgs();
      final remembered = _prefs.getString(_prefsKey);
      final selected = orgs.any((o) => o.id == state.selectedOrgId)
          ? state.selectedOrgId
          : orgs.any((o) => o.id == remembered)
              ? remembered
              : (orgs.isEmpty ? null : orgs.first.id);
      emit(state.copyWith(
          status: OrgContextStatus.loaded, orgs: orgs, selectedOrgId: selected));
    } on Failure catch (f) {
      emit(state.copyWith(status: OrgContextStatus.failure, error: f.message));
    }
  }

  Future<void> _onSelected(OrgContextSelected event, Emitter<OrgContextState> emit) async {
    await _prefs.setString(_prefsKey, event.orgId);
    emit(state.copyWith(selectedOrgId: event.orgId));
  }
}

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../roles/domain/usecases/role_usecases.dart';
import '../../../users/domain/entities/member.dart';
import '../../../users/domain/usecases/user_usecases.dart';
import '../../domain/entities/decision.dart';
import '../../domain/entities/decision_query.dart';
import '../../domain/usecases/simulate_decision.dart';

part 'simulator_event.dart';
part 'simulator_state.dart';

/// Drives the policy simulator: a query in, a would-be decision out. Persists
/// nothing — it calls the PDP's dry-run seam. Also loads the org's members and
/// the declared actions so subject/action are picked, not typed.
class SimulatorBloc extends Bloc<SimulatorEvent, SimulatorState> {
  SimulatorBloc({
    required SimulateDecision simulate,
    required ListMembers listMembers,
    required GetPermissionCatalog getPermissionCatalog,
  })  : _simulate = simulate,
        _listMembers = listMembers,
        _getPermissionCatalog = getPermissionCatalog,
        super(const SimulatorState()) {
    on<SimulatorContextRequested>(_onContext);
    on<SimulateRequested>(_onSimulate);
  }

  final SimulateDecision _simulate;
  final ListMembers _listMembers;
  final GetPermissionCatalog _getPermissionCatalog;

  Future<void> _onContext(SimulatorContextRequested event, Emitter<SimulatorState> emit) async {
    try {
      final results = await Future.wait<Object>([
        _listMembers(event.org),
        _getPermissionCatalog(),
      ]);
      emit(state.copyWith(
        subjects: results[0] as List<Member>,
        actions: (results[1] as List).cast<String>(),
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: SimulatorStatus.failure, error: f.message));
    }
  }

  Future<void> _onSimulate(SimulateRequested event, Emitter<SimulatorState> emit) async {
    emit(state.copyWith(status: SimulatorStatus.running));
    try {
      final decision = await _simulate(event.query);
      emit(state.copyWith(status: SimulatorStatus.result, decision: decision));
    } on Failure catch (f) {
      emit(state.copyWith(status: SimulatorStatus.failure, error: f.message));
    }
  }
}

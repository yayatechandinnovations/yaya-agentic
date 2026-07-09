import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/decision.dart';
import '../../domain/entities/decision_query.dart';
import '../../domain/usecases/simulate_decision.dart';

part 'simulator_event.dart';
part 'simulator_state.dart';

/// Drives the policy simulator: a query in, a would-be decision out. Persists
/// nothing — it calls the PDP's dry-run seam.
class SimulatorBloc extends Bloc<SimulatorEvent, SimulatorState> {
  SimulatorBloc({required SimulateDecision simulate})
      : _simulate = simulate,
        super(const SimulatorState()) {
    on<SimulateRequested>(_onSimulate);
  }

  final SimulateDecision _simulate;

  Future<void> _onSimulate(SimulateRequested event, Emitter<SimulatorState> emit) async {
    emit(state.copyWith(status: SimulatorStatus.running));
    try {
      final decision = await _simulate(event.query);
      emit(state.copyWith(status: SimulatorStatus.result, decision: decision));
    } on Failure catch (f) {
      emit(SimulatorState(status: SimulatorStatus.failure, error: f.message));
    }
  }
}

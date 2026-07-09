part of 'simulator_bloc.dart';

enum SimulatorStatus { idle, running, result, failure }

class SimulatorState extends Equatable {
  const SimulatorState({
    this.status = SimulatorStatus.idle,
    this.decision,
    this.error,
  });

  final SimulatorStatus status;
  final Decision? decision;
  final String? error;

  SimulatorState copyWith({SimulatorStatus? status, Decision? decision, String? error}) =>
      SimulatorState(
        status: status ?? this.status,
        decision: decision ?? this.decision,
        error: error,
      );

  @override
  List<Object?> get props => [status, decision, error];
}

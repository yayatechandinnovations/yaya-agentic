part of 'simulator_bloc.dart';

sealed class SimulatorEvent extends Equatable {
  const SimulatorEvent();

  @override
  List<Object?> get props => [];
}

class SimulatorContextRequested extends SimulatorEvent {
  const SimulatorContextRequested(this.org);

  final String org;

  @override
  List<Object?> get props => [org];
}

class SimulateRequested extends SimulatorEvent {
  const SimulateRequested(this.query);

  final DecisionQuery query;

  @override
  List<Object?> get props => [query];
}

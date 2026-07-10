part of 'simulator_bloc.dart';

enum SimulatorStatus { idle, running, result, failure }

class SimulatorState extends Equatable {
  const SimulatorState({
    this.status = SimulatorStatus.idle,
    this.decision,
    this.subjects = const [],
    this.actions = const [],
    this.error,
  });

  final SimulatorStatus status;
  final Decision? decision;
  final List<Member> subjects;
  final List<String> actions;
  final String? error;

  SimulatorState copyWith({
    SimulatorStatus? status,
    Decision? decision,
    List<Member>? subjects,
    List<String>? actions,
    String? error,
  }) =>
      SimulatorState(
        status: status ?? this.status,
        decision: decision ?? this.decision,
        subjects: subjects ?? this.subjects,
        actions: actions ?? this.actions,
        error: error,
      );

  @override
  List<Object?> get props => [status, decision, subjects, actions, error];
}

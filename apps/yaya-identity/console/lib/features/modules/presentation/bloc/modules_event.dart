part of 'modules_bloc.dart';

sealed class ModulesEvent extends Equatable {
  const ModulesEvent();

  @override
  List<Object?> get props => [];
}

class ModulesRequested extends ModulesEvent {
  const ModulesRequested();
}

class ModuleDeclareRequested extends ModulesEvent {
  const ModuleDeclareRequested(this.app, this.module, this.actions);

  final String app;
  final String module;
  final List<String> actions;

  @override
  List<Object?> get props => [app, module, actions];
}

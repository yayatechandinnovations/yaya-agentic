part of 'modules_bloc.dart';

enum ModulesStatus { initial, loading, loaded, failure }

class ModulesState extends Equatable {
  const ModulesState({
    this.status = ModulesStatus.initial,
    this.modules = const [],
    this.error,
    this.message,
  });

  final ModulesStatus status;
  final List<PermissionModule> modules;
  final String? error;
  final String? message;

  ModulesState copyWith({
    ModulesStatus? status,
    List<PermissionModule>? modules,
    String? error,
    String? message,
  }) =>
      ModulesState(
        status: status ?? this.status,
        modules: modules ?? this.modules,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, modules, error, message];
}

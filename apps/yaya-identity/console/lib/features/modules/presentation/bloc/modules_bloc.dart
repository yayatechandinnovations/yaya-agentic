import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/permission_module.dart';
import '../../domain/usecases/module_usecases.dart';

part 'modules_event.dart';
part 'modules_state.dart';

/// The permission-catalog authoring screen: modules (app + resource) with their
/// actions. Declaring is idempotent, so "new module" and "add actions to an
/// existing module" are the same operation.
class ModulesBloc extends Bloc<ModulesEvent, ModulesState> {
  ModulesBloc({required LoadModules loadModules, required DeclareModule declareModule})
      : _loadModules = loadModules,
        _declareModule = declareModule,
        super(const ModulesState()) {
    on<ModulesRequested>(_onRequested);
    on<ModuleDeclareRequested>(_onDeclare);
  }

  final LoadModules _loadModules;
  final DeclareModule _declareModule;

  Future<void> _onRequested(ModulesRequested event, Emitter<ModulesState> emit) async {
    emit(state.copyWith(status: ModulesStatus.loading));
    try {
      emit(state.copyWith(status: ModulesStatus.loaded, modules: await _loadModules()));
    } on Failure catch (f) {
      emit(state.copyWith(status: ModulesStatus.failure, error: f.message));
    }
  }

  Future<void> _onDeclare(ModuleDeclareRequested event, Emitter<ModulesState> emit) async {
    try {
      await _declareModule(event.app, event.module, event.actions);
      emit(state.copyWith(
          message: 'Module "${event.app}.${event.module}" now has '
              '${event.actions.length} declared action${event.actions.length == 1 ? '' : 's'}.'));
      add(const ModulesRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: ModulesStatus.failure, error: f.message));
    }
  }
}

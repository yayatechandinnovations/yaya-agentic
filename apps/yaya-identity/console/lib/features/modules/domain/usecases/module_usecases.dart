import '../entities/permission_module.dart';
import '../repositories/module_repository.dart';

class LoadModules {
  const LoadModules(this._repo);
  final ModuleRepository _repo;
  Future<List<PermissionModule>> call() async =>
      PermissionModule.fromCatalog(await _repo.catalog());
}

class DeclareModule {
  const DeclareModule(this._repo);
  final ModuleRepository _repo;
  Future<void> call(String app, String module, List<String> actions) =>
      _repo.declare(app, actions.map((a) => '$app.$module:$a').toList());
}

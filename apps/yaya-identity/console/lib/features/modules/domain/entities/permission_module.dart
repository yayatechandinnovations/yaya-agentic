import 'package:equatable/equatable.dart';

/// A module of the permission catalog — the `<app>.<module>` half of a
/// permission key, with the actions declared under it. `sales.users` with
/// actions `[read, update]` owns the keys `sales.users:read`, `sales.users:update`.
class PermissionModule extends Equatable {
  const PermissionModule({
    required this.app,
    required this.module,
    required this.actions,
  });

  final String app;
  final String module;
  final List<String> actions;

  String key(String action) => '$app.$module:$action';

  /// Group a flat catalog of `<app>.<module>:<action>` keys into modules.
  static List<PermissionModule> fromCatalog(List<String> keys) {
    final grouped = <String, List<String>>{};
    for (final key in keys) {
      final dot = key.indexOf('.');
      final colon = key.indexOf(':');
      if (dot <= 0 || colon <= dot) continue;
      grouped.putIfAbsent(key.substring(0, colon), () => []).add(key.substring(colon + 1));
    }
    final modules = grouped.entries.map((e) {
      final dot = e.key.indexOf('.');
      return PermissionModule(
        app: e.key.substring(0, dot),
        module: e.key.substring(dot + 1),
        actions: e.value..sort(),
      );
    }).toList()
      ..sort((a, b) {
        final byApp = a.app.compareTo(b.app);
        return byApp != 0 ? byApp : a.module.compareTo(b.module);
      });
    return modules;
  }

  @override
  List<Object?> get props => [app, module, actions];
}

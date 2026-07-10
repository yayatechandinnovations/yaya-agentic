abstract class ModuleRepository {
  /// Every declared permission key across apps.
  Future<List<String>> catalog();

  /// Declare permission keys under an app namespace (idempotent).
  Future<void> declare(String app, List<String> keys);
}

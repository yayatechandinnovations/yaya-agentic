import '../entities/org_settings.dart';

abstract class SettingsRepository {
  Future<OrgSettings> load(String org);
  Future<void> setMfaRequired(String org, bool required);

  /// Rotate the signing keys (make-before-break); returns the new active kid.
  Future<String> rotateKeys();
}

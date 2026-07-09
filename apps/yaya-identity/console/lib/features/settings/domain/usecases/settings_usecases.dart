import '../entities/org_settings.dart';
import '../repositories/settings_repository.dart';

class LoadOrgSettings {
  const LoadOrgSettings(this._repo);
  final SettingsRepository _repo;
  Future<OrgSettings> call(String org) => _repo.load(org);
}

class SetMfaRequired {
  const SetMfaRequired(this._repo);
  final SettingsRepository _repo;
  Future<void> call(String org, bool required) => _repo.setMfaRequired(org, required);
}

class RotateKeys {
  const RotateKeys(this._repo);
  final SettingsRepository _repo;
  Future<String> call() => _repo.rotateKeys();
}

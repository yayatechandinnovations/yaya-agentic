import 'package:dio/dio.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/org_settings.dart';
import '../../domain/repositories/settings_repository.dart';
import '../datasources/settings_data_source.dart';

class SettingsRepositoryImpl implements SettingsRepository {
  SettingsRepositoryImpl(this._remote);

  final SettingsDataSource _remote;

  @override
  Future<OrgSettings> load(String org) => _guard(() async {
        final json = await _remote.getOrg(org);
        return OrgSettings(
          org: json['id'] as String? ?? org,
          name: json['name'] as String? ?? '',
          mfaRequired: json['mfaRequired'] as bool? ?? false,
        );
      });

  @override
  Future<void> setMfaRequired(String org, bool required) =>
      _guard(() => _remote.setMfa(org, required));

  @override
  Future<String> rotateKeys() => _guard(() => _remote.rotateKeys());

  Future<T> _guard<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw const AuthFailure('session expired — please sign in again');
      }
      if (e.response?.statusCode == 404) {
        throw const ServerFailure('no such organization');
      }
      throw ServerFailure('request failed: ${e.response?.statusCode ?? e.message}');
    }
  }
}

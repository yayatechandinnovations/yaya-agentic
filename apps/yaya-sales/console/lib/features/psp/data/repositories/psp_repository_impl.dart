import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/psp_entities.dart';
import '../../domain/repositories/psp_repository.dart';
import '../datasources/psp_remote_data_source.dart';

PspAccount _accountFromJson(Map<String, dynamic> json) => PspAccount(
      provider: '${json['provider'] ?? ''}',
      status: '${json['status'] ?? 'PENDING'}',
      chargesEnabled: json['chargesEnabled'] == true,
      accountId: json['accountId'] as String?,
      onboardingSupported: json['onboardingSupported'] == true,
    );

class PspRepositoryImpl implements PspRepository {
  PspRepositoryImpl(this._remote);

  final PspRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<PspAccount>> status() =>
      _guard(() async => (await _remote.status()).map(_accountFromJson).toList());

  @override
  Future<OnboardingLink> onboardingLink(String provider, String returnUrl) =>
      _guard(() async {
        final json = await _remote.onboardingLink(provider, returnUrl);
        return OnboardingLink(
          actionUrl: '${json['actionUrl'] ?? ''}',
          trackingRef: json['trackingRef'] as String?,
        );
      });

  @override
  Future<PspAccount> reconcileReturn(String provider, Map<String, dynamic> params) =>
      _guard(() async => _accountFromJson(await _remote.reconcileReturn(provider, params)));

  @override
  Future<PspAccount> disconnect(String provider) =>
      _guard(() async => _accountFromJson(await _remote.disconnect(provider)));
}

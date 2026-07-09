import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/fleet_entities.dart';
import '../../domain/repositories/fleet_repository.dart';
import '../datasources/fleet_remote_data_source.dart';
import '../models/fleet_models.dart';

class FleetRepositoryImpl implements FleetRepository {
  FleetRepositoryImpl(this._remote);

  final FleetRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<FleetInvite> mintInvite({int? ttlHours}) =>
      _guard(() async => inviteFromJson(await _remote.mintInvite(ttlHours)));

  @override
  Future<List<DeliveryAgent>> agents({String? status}) =>
      _guard(() async => (await _remote.agents(status: status)).map(agentFromJson).toList());

  @override
  Future<DeliveryAgent> agent(String id) =>
      _guard(() async => agentFromJson(await _remote.agent(id)));

  @override
  Future<String> documentContent(String agentId, String documentId) =>
      _guard(() async =>
          '${(await _remote.documentContent(agentId, documentId))['contentBase64'] ?? ''}');

  @override
  Future<CourierCredential> approve(String agentId) =>
      _guard(() async => credentialFromJson(await _remote.approve(agentId)));

  @override
  Future<DeliveryAgent> reject(String agentId, String? reason) =>
      _guard(() async => agentFromJson(await _remote.reject(agentId, reason)));

  @override
  Future<DeliveryAgent> suspend(String agentId, String? reason) =>
      _guard(() async => agentFromJson(await _remote.suspend(agentId, reason)));
}

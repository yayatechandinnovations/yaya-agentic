import '../entities/fleet_entities.dart';

abstract class FleetRepository {
  /// Mints a single-use invite. The token is returned exactly once.
  Future<FleetInvite> mintInvite({int? ttlHours});

  Future<List<DeliveryAgent>> agents({String? status});
  Future<DeliveryAgent> agent(String id);

  /// Decrypt-on-demand: the only call that returns document plaintext.
  Future<String> documentContent(String agentId, String documentId);

  /// Approval mints the operational courier token — returned exactly once.
  Future<CourierCredential> approve(String agentId);

  Future<DeliveryAgent> reject(String agentId, String? reason);
  Future<DeliveryAgent> suspend(String agentId, String? reason);
}

import '../entities/fleet_entities.dart';
import '../repositories/fleet_repository.dart';

class MintInvite {
  const MintInvite(this._repo);
  final FleetRepository _repo;
  Future<FleetInvite> call({int? ttlHours}) => _repo.mintInvite(ttlHours: ttlHours);
}

class ListAgents {
  const ListAgents(this._repo);
  final FleetRepository _repo;
  Future<List<DeliveryAgent>> call({String? status}) => _repo.agents(status: status);
}

class GetAgent {
  const GetAgent(this._repo);
  final FleetRepository _repo;
  Future<DeliveryAgent> call(String id) => _repo.agent(id);
}

class ReadDocumentContent {
  const ReadDocumentContent(this._repo);
  final FleetRepository _repo;
  Future<String> call(String agentId, String documentId) =>
      _repo.documentContent(agentId, documentId);
}

class ApproveAgent {
  const ApproveAgent(this._repo);
  final FleetRepository _repo;
  Future<CourierCredential> call(String agentId) => _repo.approve(agentId);
}

class RejectAgent {
  const RejectAgent(this._repo);
  final FleetRepository _repo;
  Future<DeliveryAgent> call(String agentId, String? reason) =>
      _repo.reject(agentId, reason);
}

class SuspendAgent {
  const SuspendAgent(this._repo);
  final FleetRepository _repo;
  Future<DeliveryAgent> call(String agentId, String? reason) =>
      _repo.suspend(agentId, reason);
}

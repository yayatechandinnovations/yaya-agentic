part of 'fleet_bloc.dart';

sealed class FleetEvent extends Equatable {
  const FleetEvent();

  @override
  List<Object?> get props => [];
}

class AgentsRequested extends FleetEvent {
  const AgentsRequested({this.statusFilter});

  final String? statusFilter;

  @override
  List<Object?> get props => [statusFilter];
}

class InviteMintRequested extends FleetEvent {
  const InviteMintRequested({this.ttlHours});

  final int? ttlHours;

  @override
  List<Object?> get props => [ttlHours];
}

class AgentDetailRequested extends FleetEvent {
  const AgentDetailRequested(this.agentId);

  final String agentId;

  @override
  List<Object?> get props => [agentId];
}

class DocumentContentRequested extends FleetEvent {
  const DocumentContentRequested(this.agentId, this.documentId);

  final String agentId;
  final String documentId;

  @override
  List<Object?> get props => [agentId, documentId];
}

class AgentApproveRequested extends FleetEvent {
  const AgentApproveRequested(this.agentId);

  final String agentId;

  @override
  List<Object?> get props => [agentId];
}

class AgentRejectRequested extends FleetEvent {
  const AgentRejectRequested(this.agentId, this.reason);

  final String agentId;
  final String? reason;

  @override
  List<Object?> get props => [agentId, reason];
}

class AgentSuspendRequested extends FleetEvent {
  const AgentSuspendRequested(this.agentId, this.reason);

  final String agentId;
  final String? reason;

  @override
  List<Object?> get props => [agentId, reason];
}

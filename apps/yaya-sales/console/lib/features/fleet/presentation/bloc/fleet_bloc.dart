import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/fleet_entities.dart';
import '../../domain/usecases/fleet_usecases.dart';

part 'fleet_event.dart';
part 'fleet_state.dart';

/// The delivery fleet desk: invites, KYC review, approve/reject/suspend.
/// One-time secrets (invite token, courier credential) land in state exactly
/// once — the page shows them and the next event clears them.
class FleetBloc extends Bloc<FleetEvent, FleetState> {
  FleetBloc({
    required MintInvite mintInvite,
    required ListAgents listAgents,
    required GetAgent getAgent,
    required ReadDocumentContent readDocument,
    required ApproveAgent approveAgent,
    required RejectAgent rejectAgent,
    required SuspendAgent suspendAgent,
  })  : _mintInvite = mintInvite,
        _listAgents = listAgents,
        _getAgent = getAgent,
        _readDocument = readDocument,
        _approveAgent = approveAgent,
        _rejectAgent = rejectAgent,
        _suspendAgent = suspendAgent,
        super(const FleetState()) {
    on<AgentsRequested>(_onAgentsRequested);
    on<InviteMintRequested>(_onInviteMint);
    on<AgentDetailRequested>(_onAgentDetail);
    on<DocumentContentRequested>(_onDocumentContent);
    on<AgentApproveRequested>(_onApprove);
    on<AgentRejectRequested>(_onReject);
    on<AgentSuspendRequested>(_onSuspend);
  }

  final MintInvite _mintInvite;
  final ListAgents _listAgents;
  final GetAgent _getAgent;
  final ReadDocumentContent _readDocument;
  final ApproveAgent _approveAgent;
  final RejectAgent _rejectAgent;
  final SuspendAgent _suspendAgent;

  Future<void> _onAgentsRequested(AgentsRequested event, Emitter<FleetState> emit) async {
    emit(state.copyWith(status: FleetStatus.loading, statusFilter: event.statusFilter));
    try {
      final agents = await _listAgents(status: event.statusFilter);
      emit(state.copyWith(status: FleetStatus.loaded, agents: agents));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  Future<void> _onInviteMint(InviteMintRequested event, Emitter<FleetState> emit) async {
    try {
      final invite = await _mintInvite(ttlHours: event.ttlHours);
      emit(state.copyWith(mintedInvite: invite));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  Future<void> _onAgentDetail(AgentDetailRequested event, Emitter<FleetState> emit) async {
    try {
      final agent = await _getAgent(event.agentId);
      emit(state.copyWith(selected: agent));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  Future<void> _onDocumentContent(
      DocumentContentRequested event, Emitter<FleetState> emit) async {
    try {
      final content = await _readDocument(event.agentId, event.documentId);
      emit(state.copyWith(documentContent: content, documentId: event.documentId));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  /// Approval gate: mints the operational credential — surfaced exactly once.
  Future<void> _onApprove(AgentApproveRequested event, Emitter<FleetState> emit) async {
    try {
      final credential = await _approveAgent(event.agentId);
      emit(state.copyWith(mintedCredential: credential));
      add(AgentDetailRequested(event.agentId));
      add(AgentsRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  Future<void> _onReject(AgentRejectRequested event, Emitter<FleetState> emit) async {
    try {
      final agent = await _rejectAgent(event.agentId, event.reason);
      emit(state.copyWith(selected: agent));
      add(AgentsRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }

  Future<void> _onSuspend(AgentSuspendRequested event, Emitter<FleetState> emit) async {
    try {
      final agent = await _suspendAgent(event.agentId, event.reason);
      emit(state.copyWith(selected: agent));
      add(AgentsRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: FleetStatus.failure, error: f.message));
    }
  }
}

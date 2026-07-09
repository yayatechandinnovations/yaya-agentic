import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/fleet/domain/entities/fleet_entities.dart';
import 'package:yaya_sales_console/features/fleet/domain/repositories/fleet_repository.dart';
import 'package:yaya_sales_console/features/fleet/domain/usecases/fleet_usecases.dart';
import 'package:yaya_sales_console/features/fleet/presentation/bloc/fleet_bloc.dart';

class MockFleetRepository extends Mock implements FleetRepository {}

void main() {
  late MockFleetRepository repo;

  FleetBloc build() => FleetBloc(
        mintInvite: MintInvite(repo),
        listAgents: ListAgents(repo),
        getAgent: GetAgent(repo),
        readDocument: ReadDocumentContent(repo),
        approveAgent: ApproveAgent(repo),
        rejectAgent: RejectAgent(repo),
        suspendAgent: SuspendAgent(repo),
      );

  setUp(() => repo = MockFleetRepository());

  const pending = DeliveryAgent(id: 'agent_1', legalName: 'Juan', status: 'UNDER_REVIEW');
  const approved = DeliveryAgent(id: 'agent_1', legalName: 'Juan', status: 'APPROVED');

  group('FleetBloc', () {
    blocTest<FleetBloc, FleetState>(
      'AgentsRequested → loading, loaded with the status filter applied',
      setUp: () => when(() => repo.agents(status: 'UNDER_REVIEW'))
          .thenAnswer((_) async => const [pending]),
      build: build,
      act: (b) => b.add(const AgentsRequested(statusFilter: 'UNDER_REVIEW')),
      expect: () => [
        const FleetState(status: FleetStatus.loading, statusFilter: 'UNDER_REVIEW'),
        const FleetState(
          status: FleetStatus.loaded,
          statusFilter: 'UNDER_REVIEW',
          agents: [pending],
        ),
      ],
    );

    blocTest<FleetBloc, FleetState>(
      'mint invite exposes the one-time token exactly once',
      setUp: () {
        when(() => repo.mintInvite(ttlHours: 48))
            .thenAnswer((_) async => const FleetInvite(token: 'invite-token-once'));
        when(() => repo.agents(status: any(named: 'status')))
            .thenAnswer((_) async => const [pending]);
      },
      build: build,
      act: (b) => b.add(const InviteMintRequested(ttlHours: 48)),
      expect: () => [
        const FleetState(mintedInvite: FleetInvite(token: 'invite-token-once')),
      ],
    );

    blocTest<FleetBloc, FleetState>(
      'approve mints the courier credential ONCE, then refreshes detail + list',
      setUp: () {
        when(() => repo.approve('agent_1')).thenAnswer(
            (_) async => const CourierCredential(agentId: 'agent_1', token: 'op-token'));
        when(() => repo.agent('agent_1')).thenAnswer((_) async => approved);
        when(() => repo.agents(status: any(named: 'status')))
            .thenAnswer((_) async => const [approved]);
        when(() => repo.agents(status: null)).thenAnswer((_) async => const [approved]);
      },
      build: build,
      act: (b) => b.add(const AgentApproveRequested('agent_1')),
      expect: () => [
        // Credential visible in exactly one emission…
        const FleetState(
            mintedCredential: CourierCredential(agentId: 'agent_1', token: 'op-token')),
        // …and gone again on the follow-up refreshes (never re-emitted).
        const FleetState(status: FleetStatus.loading),
        const FleetState(status: FleetStatus.loading, selected: approved),
        const FleetState(
            status: FleetStatus.loaded, selected: approved, agents: [approved]),
      ],
      verify: (b) {
        verify(() => repo.approve('agent_1')).called(1);
        expect(b.state.mintedCredential, isNull,
            reason: 'the one-time token must not persist in state');
      },
    );

    blocTest<FleetBloc, FleetState>(
      'approve failure (KYC gate) surfaces the server reason',
      setUp: () => when(() => repo.approve(any())).thenThrow(
          const ServerFailure('phone not verified and photo id missing')),
      build: build,
      act: (b) => b.add(const AgentApproveRequested('agent_1')),
      expect: () => [
        const FleetState(
          status: FleetStatus.failure,
          error: 'phone not verified and photo id missing',
        ),
      ],
      verify: (_) => verifyNever(() => repo.agent(any())),
    );

    blocTest<FleetBloc, FleetState>(
      'reject updates the selected agent and refreshes the list',
      setUp: () {
        const rejected =
            DeliveryAgent(id: 'agent_1', legalName: 'Juan', status: 'REJECTED');
        when(() => repo.reject('agent_1', 'blurry id scan'))
            .thenAnswer((_) async => rejected);
        when(() => repo.agents(status: null)).thenAnswer((_) async => const [rejected]);
      },
      build: build,
      act: (b) => b.add(const AgentRejectRequested('agent_1', 'blurry id scan')),
      verify: (b) {
        verify(() => repo.reject('agent_1', 'blurry id scan')).called(1);
        expect(b.state.selected?.status, 'REJECTED');
      },
    );

    blocTest<FleetBloc, FleetState>(
      'document content is decrypt-on-demand',
      setUp: () => when(() => repo.documentContent('agent_1', 'doc_1'))
          .thenAnswer((_) async => 'aGVsbG8='),
      build: build,
      act: (b) => b.add(const DocumentContentRequested('agent_1', 'doc_1')),
      expect: () => [
        const FleetState(documentId: 'doc_1', documentContent: 'aGVsbG8='),
      ],
    );
  });
}

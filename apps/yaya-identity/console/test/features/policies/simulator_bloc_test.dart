import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/decision.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/decision_query.dart';
import 'package:yaya_identity_console/features/policies/domain/repositories/policy_repository.dart';
import 'package:yaya_identity_console/features/policies/domain/usecases/simulate_decision.dart';
import 'package:yaya_identity_console/features/policies/presentation/bloc/simulator_bloc.dart';

class MockPolicyRepository extends Mock implements PolicyRepository {}

void main() {
  late MockPolicyRepository repo;

  SimulatorBloc build() => SimulatorBloc(simulate: SimulateDecision(repo));

  setUpAll(() => registerFallbackValue(
      const DecisionQuery(org: 'o', subject: 's', action: 'a')));
  setUp(() => repo = MockPolicyRepository());

  const query = DecisionQuery(
      org: 'org_1', subject: 'usr_1', action: 'sales.order:refund', resourceType: 'store', resourceId: 'st_A');

  blocTest<SimulatorBloc, SimulatorState>(
    'permit → running, result(permit)',
    setUp: () => when(() => repo.simulate(any())).thenAnswer((_) async => const Decision(permit: true)),
    build: build,
    act: (b) => b.add(const SimulateRequested(query)),
    expect: () => [
      const SimulatorState(status: SimulatorStatus.running),
      const SimulatorState(status: SimulatorStatus.result, decision: Decision(permit: true)),
    ],
  );

  blocTest<SimulatorBloc, SimulatorState>(
    'deny surfaces both reasons distinctly (#3)',
    setUp: () => when(() => repo.simulate(any())).thenAnswer((_) async => const Decision(
          permit: false,
          userSafeReason: "You can't refund over your limit.",
          auditReason: 'policy refund-cap: 750 > 500',
        )),
    build: build,
    act: (b) => b.add(const SimulateRequested(query)),
    expect: () => [
      const SimulatorState(status: SimulatorStatus.running),
      const SimulatorState(
        status: SimulatorStatus.result,
        decision: Decision(
          permit: false,
          userSafeReason: "You can't refund over your limit.",
          auditReason: 'policy refund-cap: 750 > 500',
        ),
      ),
    ],
  );

  blocTest<SimulatorBloc, SimulatorState>(
    'failure → running, failure',
    setUp: () => when(() => repo.simulate(any())).thenThrow(const ServerFailure('boom')),
    build: build,
    act: (b) => b.add(const SimulateRequested(query)),
    expect: () => [
      const SimulatorState(status: SimulatorStatus.running),
      const SimulatorState(status: SimulatorStatus.failure, error: 'boom'),
    ],
  );
}

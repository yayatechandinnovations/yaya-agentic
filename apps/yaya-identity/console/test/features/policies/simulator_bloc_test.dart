import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/decision.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/decision_query.dart';
import 'package:yaya_identity_console/features/policies/domain/repositories/policy_repository.dart';
import 'package:yaya_identity_console/features/policies/domain/usecases/simulate_decision.dart';
import 'package:yaya_identity_console/features/policies/presentation/bloc/simulator_bloc.dart';
import 'package:yaya_identity_console/features/roles/domain/repositories/role_repository.dart';
import 'package:yaya_identity_console/features/roles/domain/usecases/role_usecases.dart';
import 'package:yaya_identity_console/features/users/domain/entities/member.dart';
import 'package:yaya_identity_console/features/users/domain/repositories/user_repository.dart';
import 'package:yaya_identity_console/features/users/domain/usecases/user_usecases.dart';

class MockPolicyRepository extends Mock implements PolicyRepository {}

class MockUserRepository extends Mock implements UserRepository {}

class MockRoleRepository extends Mock implements RoleRepository {}

void main() {
  late MockPolicyRepository repo;
  late MockUserRepository userRepo;
  late MockRoleRepository roleRepo;

  SimulatorBloc build() => SimulatorBloc(
        simulate: SimulateDecision(repo),
        listMembers: ListMembers(userRepo),
        getPermissionCatalog: GetPermissionCatalog(roleRepo),
      );

  setUpAll(() => registerFallbackValue(
      const DecisionQuery(org: 'o', subject: 's', action: 'a')));
  setUp(() {
    repo = MockPolicyRepository();
    userRepo = MockUserRepository();
    roleRepo = MockRoleRepository();
  });

  const query = DecisionQuery(
      org: 'org_1', subject: 'usr_1', action: 'sales.order:refund', resourceType: 'store', resourceId: 'st_A');

  blocTest<SimulatorBloc, SimulatorState>(
    'SimulatorContextRequested loads the subject and action pickers',
    setUp: () {
      when(() => userRepo.members(any())).thenAnswer(
          (_) async => const [Member(userId: 'usr_1', status: 'ACTIVE')]);
      when(roleRepo.permissionCatalog)
          .thenAnswer((_) async => const ['sales.order:refund']);
    },
    build: build,
    act: (b) => b.add(const SimulatorContextRequested('org_1')),
    expect: () => [
      isA<SimulatorState>()
          .having((s) => s.subjects.single.userId, 'subject', 'usr_1')
          .having((s) => s.actions, 'actions', const ['sales.order:refund']),
    ],
  );

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

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/policy.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/policy_draft.dart';
import 'package:yaya_identity_console/features/policies/domain/repositories/policy_admin_repository.dart';
import 'package:yaya_identity_console/features/policies/domain/usecases/policy_admin_usecases.dart';
import 'package:yaya_identity_console/features/policies/presentation/bloc/policy_editor_bloc.dart';
import 'package:yaya_identity_console/features/roles/domain/repositories/role_repository.dart';
import 'package:yaya_identity_console/features/roles/domain/usecases/role_usecases.dart';

class MockPolicyAdminRepository extends Mock implements PolicyAdminRepository {}

class MockRoleRepository extends Mock implements RoleRepository {}

void main() {
  late MockPolicyAdminRepository repo;
  late MockRoleRepository roleRepo;

  PolicyEditorBloc build() => PolicyEditorBloc(
        listPolicies: ListPolicies(repo),
        createPolicy: CreatePolicy(repo),
        getPermissionCatalog: GetPermissionCatalog(roleRepo),
      );

  setUpAll(() => registerFallbackValue(
      const PolicyDraft(org: 'o', name: 'n', effect: 'DENY', targetJson: '{}')));
  setUp(() {
    repo = MockPolicyAdminRepository();
    roleRepo = MockRoleRepository();
    when(roleRepo.permissionCatalog)
        .thenAnswer((_) async => const ['sales.order:refund']);
  });

  const policies = [Policy(id: 'pol_1', name: 'refund-cap', effect: 'DENY', priority: 100)];
  const draft = PolicyDraft(
    org: 'org_1',
    name: 'refund-cap',
    effect: 'DENY',
    targetJson: '{"actions":["sales.order:refund"]}',
    userReason: 'blocked',
    auditReason: 'over cap',
  );

  blocTest<PolicyEditorBloc, PolicyEditorState>(
    'PoliciesRequested → loading, loaded',
    setUp: () => when(() => repo.list(any())).thenAnswer((_) async => policies),
    build: build,
    act: (b) => b.add(const PoliciesRequested('org_1')),
    expect: () => [
      const PolicyEditorState(status: PolicyEditorStatus.loading, org: 'org_1'),
      isA<PolicyEditorState>()
          .having((s) => s.status, 'status', PolicyEditorStatus.loaded)
          .having((s) => s.policies, 'policies', policies)
          .having((s) => s.catalog, 'catalog', const ['sales.order:refund']),
    ],
  );

  blocTest<PolicyEditorBloc, PolicyEditorState>(
    'PolicyCreateRequested creates then refreshes',
    setUp: () {
      when(() => repo.create(any())).thenAnswer((_) async => policies.first);
      when(() => repo.list(any())).thenAnswer((_) async => policies);
    },
    build: build,
    act: (b) => b.add(const PolicyCreateRequested(draft)),
    expect: () => [
      isA<PolicyEditorState>().having((s) => s.message, 'message', 'Policy "refund-cap" created.'),
      isA<PolicyEditorState>().having((s) => s.status, 'status', PolicyEditorStatus.loading),
      isA<PolicyEditorState>()
          .having((s) => s.status, 'status', PolicyEditorStatus.loaded)
          .having((s) => s.policies, 'policies', policies),
    ],
  );

  blocTest<PolicyEditorBloc, PolicyEditorState>(
    'PolicyCreateRequested surfaces the two-reason rejection',
    setUp: () => when(() => repo.create(any())).thenThrow(
        const ServerFailure('rejected: a DENY policy needs two distinct reasons, and JSON must be valid')),
    build: build,
    act: (b) => b.add(const PolicyCreateRequested(draft)),
    expect: () => [
      isA<PolicyEditorState>()
          .having((s) => s.status, 'status', PolicyEditorStatus.failure)
          .having((s) => s.error, 'error', contains('two distinct reasons')),
    ],
  );
}

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/policy.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/policy_draft.dart';
import 'package:yaya_identity_console/features/policies/domain/repositories/policy_admin_repository.dart';
import 'package:yaya_identity_console/features/policies/domain/usecases/policy_admin_usecases.dart';
import 'package:yaya_identity_console/features/policies/presentation/bloc/policy_editor_bloc.dart';

class MockPolicyAdminRepository extends Mock implements PolicyAdminRepository {}

void main() {
  late MockPolicyAdminRepository repo;

  PolicyEditorBloc build() =>
      PolicyEditorBloc(listPolicies: ListPolicies(repo), createPolicy: CreatePolicy(repo));

  setUpAll(() => registerFallbackValue(
      const PolicyDraft(org: 'o', name: 'n', effect: 'DENY', targetJson: '{}')));
  setUp(() => repo = MockPolicyAdminRepository());

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
      const PolicyEditorState(status: PolicyEditorStatus.loaded, policies: policies, org: 'org_1'),
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
      const PolicyEditorState(status: PolicyEditorStatus.loading, org: 'org_1'),
      const PolicyEditorState(status: PolicyEditorStatus.loaded, policies: policies, org: 'org_1'),
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

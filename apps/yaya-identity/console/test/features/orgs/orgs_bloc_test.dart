import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/orgs/domain/entities/organization.dart';
import 'package:yaya_identity_console/features/orgs/domain/repositories/org_repository.dart';
import 'package:yaya_identity_console/features/orgs/domain/usecases/org_usecases.dart';
import 'package:yaya_identity_console/features/orgs/presentation/bloc/orgs_bloc.dart';

class MockOrgRepository extends Mock implements OrgRepository {}

void main() {
  late MockOrgRepository repo;

  OrgsBloc build() => OrgsBloc(
        listOrgs: ListOrgs(repo),
        createOrg: CreateOrg(repo),
        updateOrg: UpdateOrg(repo),
      );

  setUpAll(() => registerFallbackValue(const OrgDraft(name: 'x')));
  setUp(() => repo = MockOrgRepository());

  const orgs = [Organization(id: 'org_1', name: 'Acme', slug: 'acme')];

  blocTest<OrgsBloc, OrgsState>(
    'OrgsRequested → loading, loaded',
    setUp: () => when(repo.list).thenAnswer((_) async => orgs),
    build: build,
    act: (b) => b.add(const OrgsRequested()),
    expect: () => [
      const OrgsState(status: OrgsStatus.loading),
      const OrgsState(status: OrgsStatus.loaded, orgs: orgs),
    ],
  );

  blocTest<OrgsBloc, OrgsState>(
    'OrgsRequested failure → loading, failure',
    setUp: () => when(repo.list).thenThrow(const NetworkFailure('offline')),
    build: build,
    act: (b) => b.add(const OrgsRequested()),
    expect: () => [
      const OrgsState(status: OrgsStatus.loading),
      const OrgsState(status: OrgsStatus.failure, error: 'offline'),
    ],
  );

  blocTest<OrgsBloc, OrgsState>(
    'OrgCreateRequested creates (full draft) then refreshes the list',
    setUp: () {
      when(() => repo.create(any()))
          .thenAnswer((_) async => const Organization(id: 'org_2', name: 'New', slug: 'new'));
      when(repo.list).thenAnswer((_) async => orgs);
    },
    build: build,
    act: (b) => b.add(const OrgCreateRequested(
        OrgDraft(name: 'New', description: 'Retailer', country: 'DO'))),
    expect: () => [
      isA<OrgsState>().having((s) => s.message, 'message', 'Organization "New" created.'),
      isA<OrgsState>().having((s) => s.status, 'status', OrgsStatus.loading),
      isA<OrgsState>().having((s) => s.orgs, 'orgs', orgs),
    ],
    verify: (_) => verify(() => repo.create(
        const OrgDraft(name: 'New', description: 'Retailer', country: 'DO'))).called(1),
  );

  blocTest<OrgsBloc, OrgsState>(
    'OrgUpdateRequested updates the profile then refreshes',
    setUp: () {
      when(() => repo.update(any(), any())).thenAnswer(
          (_) async => const Organization(id: 'org_1', name: 'Acme Intl', slug: 'acme'));
      when(repo.list).thenAnswer((_) async => orgs);
    },
    build: build,
    act: (b) => b.add(const OrgUpdateRequested('org_1', OrgDraft(name: 'Acme Intl'))),
    expect: () => [
      isA<OrgsState>().having((s) => s.message, 'message', 'Organization updated.'),
      isA<OrgsState>().having((s) => s.status, 'status', OrgsStatus.loading),
      isA<OrgsState>().having((s) => s.orgs, 'orgs', orgs),
    ],
    verify: (_) =>
        verify(() => repo.update('org_1', const OrgDraft(name: 'Acme Intl'))).called(1),
  );
}

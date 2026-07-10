import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yaya_identity_console/core/context/org_context_bloc.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/orgs/domain/entities/organization.dart';
import 'package:yaya_identity_console/features/orgs/domain/repositories/org_repository.dart';
import 'package:yaya_identity_console/features/orgs/domain/usecases/org_usecases.dart';

class MockOrgRepository extends Mock implements OrgRepository {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late MockOrgRepository repo;
  late SharedPreferences prefs;

  const orgs = [
    Organization(id: 'org_1', name: 'Acme', slug: 'acme'),
    Organization(id: 'org_2', name: 'Beta', slug: 'beta'),
  ];

  OrgContextBloc build() => OrgContextBloc(listOrgs: ListOrgs(repo), prefs: prefs);

  setUp(() async {
    repo = MockOrgRepository();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  blocTest<OrgContextBloc, OrgContextState>(
    'auto-selects the first org when nothing is remembered',
    setUp: () => when(repo.list).thenAnswer((_) async => orgs),
    build: build,
    act: (b) => b.add(const OrgContextRequested()),
    expect: () => [
      const OrgContextState(status: OrgContextStatus.loading),
      const OrgContextState(
          status: OrgContextStatus.loaded, orgs: orgs, selectedOrgId: 'org_1'),
    ],
  );

  blocTest<OrgContextBloc, OrgContextState>(
    'honors the remembered selection when it still exists',
    setUp: () async {
      when(repo.list).thenAnswer((_) async => orgs);
      await prefs.setString('org_scope', 'org_2');
    },
    build: build,
    act: (b) => b.add(const OrgContextRequested()),
    expect: () => [
      const OrgContextState(status: OrgContextStatus.loading),
      const OrgContextState(
          status: OrgContextStatus.loaded, orgs: orgs, selectedOrgId: 'org_2'),
    ],
  );

  blocTest<OrgContextBloc, OrgContextState>(
    'selection is persisted for the next session',
    setUp: () => when(repo.list).thenAnswer((_) async => orgs),
    build: build,
    act: (b) async {
      b.add(const OrgContextRequested());
      await Future<void>.delayed(Duration.zero);
      b.add(const OrgContextSelected('org_2'));
    },
    verify: (_) => expect(prefs.getString('org_scope'), 'org_2'),
  );

  blocTest<OrgContextBloc, OrgContextState>(
    'load failure is surfaced',
    setUp: () => when(repo.list).thenThrow(const NetworkFailure('offline')),
    build: build,
    act: (b) => b.add(const OrgContextRequested()),
    expect: () => [
      const OrgContextState(status: OrgContextStatus.loading),
      const OrgContextState(status: OrgContextStatus.failure, error: 'offline'),
    ],
  );
}

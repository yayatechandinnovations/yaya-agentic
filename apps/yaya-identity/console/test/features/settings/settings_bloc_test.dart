import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/settings/domain/entities/org_settings.dart';
import 'package:yaya_identity_console/features/settings/domain/repositories/settings_repository.dart';
import 'package:yaya_identity_console/features/settings/domain/usecases/settings_usecases.dart';
import 'package:yaya_identity_console/features/settings/presentation/bloc/settings_bloc.dart';

class MockSettingsRepository extends Mock implements SettingsRepository {}

void main() {
  late MockSettingsRepository repo;

  SettingsBloc build() => SettingsBloc(
        loadOrgSettings: LoadOrgSettings(repo),
        setMfaRequired: SetMfaRequired(repo),
        rotateKeys: RotateKeys(repo),
      );

  setUp(() => repo = MockSettingsRepository());

  const settings = OrgSettings(org: 'org_1', name: 'Acme', mfaRequired: false);

  blocTest<SettingsBloc, SettingsState>(
    'SettingsRequested → loading, loaded',
    setUp: () => when(() => repo.load(any())).thenAnswer((_) async => settings),
    build: build,
    act: (b) => b.add(const SettingsRequested('org_1')),
    expect: () => [
      const SettingsState(status: SettingsStatus.loading),
      const SettingsState(status: SettingsStatus.loaded, settings: settings),
    ],
  );

  blocTest<SettingsBloc, SettingsState>(
    'MfaToggled sets then reloads',
    setUp: () {
      when(() => repo.setMfaRequired(any(), any())).thenAnswer((_) async {});
      when(() => repo.load(any())).thenAnswer(
          (_) async => const OrgSettings(org: 'org_1', name: 'Acme', mfaRequired: true));
    },
    build: build,
    act: (b) => b.add(const MfaToggled('org_1', true)),
    expect: () => [
      const SettingsState(status: SettingsStatus.loading),
      isA<SettingsState>().having((s) => s.settings?.mfaRequired, 'mfaRequired', true),
    ],
    verify: (_) => verify(() => repo.setMfaRequired('org_1', true)).called(1),
  );

  blocTest<SettingsBloc, SettingsState>(
    'KeysRotateRequested reports the new kid',
    setUp: () => when(repo.rotateKeys).thenAnswer((_) async => 'kid_new'),
    build: build,
    act: (b) => b.add(const KeysRotateRequested()),
    expect: () => [
      isA<SettingsState>().having((s) => s.message, 'message', contains('kid_new')),
    ],
  );
}

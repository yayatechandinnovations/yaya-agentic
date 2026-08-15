import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/network/network_settings_screen.dart';
import 'package:yayaagenticweb/models/admin/network_settings.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this._settings) : super(Dio());

  NetworkSettings _settings;
  NetworkSettings? lastSaved;

  @override
  Future<NetworkSettings> getNetworkSettings(String tenantId) async => _settings;

  @override
  Future<NetworkSettings> updateNetworkSettings(String tenantId, NetworkSettings body) async {
    lastSaved = body;
    _settings = body;
    return body;
  }
}

Widget _wrap(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: NetworkSettingsScreen())),
    );

void main() {
  testWidgets('hydrates from backend and saves toggles', (tester) async {
    tester.view.physicalSize = const Size(1400, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    final api = _FakeAdminApi(NetworkSettings(
      enabled: true, playEnabled: false, claimEnabled: false,
      hubBaseUrl: 'https://hub.example', hubCredentialRef: 'REF',
      entryPointHandle: 'e_abc', entryPointActive: true, chatUrl: 'https://app/e/e_abc',
    ));

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    // Loaded state shows the switches + the provisioned front door.
    expect(find.text('Network chat (Corillo)'), findsOneWidget);
    expect(find.text('Enabled'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);

    // Turn Play on and save.
    final playSwitch = find.widgetWithText(SwitchListTile, 'Play');
    await tester.tap(playSwitch);
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(api.lastSaved, isNotNull);
    expect(api.lastSaved!.playEnabled, true);
    expect(api.lastSaved!.enabled, true);
  });

  testWidgets('play/claim disabled until enabled is on', (tester) async {
    tester.view.physicalSize = const Size(1400, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    final api = _FakeAdminApi(NetworkSettings(
      enabled: false, playEnabled: false, claimEnabled: false));

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    // With Enabled off, the Play switch is disabled (onChanged null).
    final play = tester.widget<SwitchListTile>(find.widgetWithText(SwitchListTile, 'Play'));
    expect(play.onChanged, isNull);
  });
}

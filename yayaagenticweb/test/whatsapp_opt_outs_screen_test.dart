import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_opt_outs/whatsapp_opt_outs_screen.dart';
import 'package:yayaagenticweb/models/admin/channel_opt_out.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final rows = ['+18095550001', '+18295559999'];
  final removed = <String>[];

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async => [
        WhatsAppSenderResponse.fromJson({
          'id': 'snd_1',
          'tenantId': tenantId,
          'phoneE164': '+18095551234',
          'displayName': 'Taxi Caribe',
          'businessProfile': {'legalName': 'Taxi', 'displayName': 'Taxi Caribe', 'contactEmail': 'ops@t.example'},
          'status': 'ACTIVE',
          'onboarding': {'status': 'ONLINE'},
        }),
      ];

  @override
  Future<List<ChannelOptOutResponse>> listOptOuts(String tenantId, String senderId) async => [
        for (final p in rows) ChannelOptOutResponse(senderId: senderId, endUserPhone: p, optedOutAt: '2026-11-20T10:00:00Z', reason: 'user_stop'),
      ];

  @override
  Future<void> removeOptOut(String tenantId, String senderId, String phone) async {
    removed.add(phone);
    rows.remove(phone);
  }
}

Widget _app(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: WhatsAppOptOutsScreen())),
    );

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('search by number narrows the list', (tester) async {
    await tester.pumpWidget(_app(_FakeAdminApi()));
    await tester.pumpAndSettle();
    expect(find.text('+18095550001'), findsOneWidget);
    expect(find.text('+18295559999'), findsOneWidget);
    expect(find.text('Replied STOP'), findsNWidgets(2));

    final search = find.ancestor(of: find.text('Search by phone number'), matching: find.byType(TextField));
    await tester.enterText(search, '8295559');
    await tester.pumpAndSettle();
    expect(find.text('+18095550001'), findsNothing);
    expect(find.text('+18295559999'), findsOneWidget);
  });

  testWidgets('re-subscribing says the person will get messages again, and only proceeds on confirm', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(TextButton, 'Re-subscribe').first);
    await tester.pumpAndSettle();
    expect(find.text('Re-subscribe +18095550001?'), findsOneWidget);
    expect(find.textContaining('will start receiving WhatsApp messages from Taxi Caribe'), findsOneWidget);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Cancel'));
    await tester.pumpAndSettle();
    expect(api.removed, isEmpty);

    await tester.tap(find.widgetWithText(TextButton, 'Re-subscribe').first);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Re-subscribe'));
    await tester.pumpAndSettle();
    expect(api.removed, ['+18095550001']);
    expect(find.text('+18095550001'), findsNothing);
  });
}

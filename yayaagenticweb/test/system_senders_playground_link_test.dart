import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/system_senders/system_senders_screen.dart';
import 'package:yayaagenticweb/models/admin/chat_entry_point.dart';
import 'package:yayaagenticweb/models/admin/system_sender.dart';
import 'package:yayaagenticweb/models/admin/tool.dart';
import 'package:yayaagenticweb/models/admin/tenant_jwt_secret.dart';
import 'package:yayaagenticweb/models/admin/tenant_oidc_issuer.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

const _simulator = 'snd_playground_sim__t1';

Map<String, dynamic> _whatsappSender(String id, String phone, String name) => {
      'id': id,
      'tenantId': 't1',
      'phoneE164': phone,
      'displayName': name,
      'businessProfile': {'legalName': name, 'displayName': name, 'contactEmail': 'ops@t.example'},
      'status': 'ACTIVE',
      'onboarding': {'status': 'ONLINE'},
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.linked) : super(Dio());

  final String? linked;

  @override
  Future<List<SystemSenderResponse>> listSystemSenders(String tenantId) async => [
        SystemSenderResponse.fromJson({
          'id': 'sys_1',
          'tenantId': 't1',
          'entryPoint': 'ep_1',
          'displayName': 'Ride updates',
          'enabled': true,
          'trust': {'authenticatorName': 'shared-secret-jwt', 'credentialRef': 'yaya-go-integration'},
          'mediation': 'VERBATIM',
          'addressing': const {},
          'whatsappSenderId': ?linked,
        }),
      ];

  @override
  Future<List<ChatEntryPointResponse>> listChatEntryPoints(String tenantId) async => [
        ChatEntryPointResponse.fromJson({
          'id': 'ep_1',
          'tenantId': 't1',
          'handle': 'customers',
          'profileId': 'taxi-customer',
          'displayName': 'Customers',
        }),
      ];

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async => [
        WhatsAppSenderResponse.fromJson(_whatsappSender(_simulator, '+155501001234', 'Playground (simulated)')),
        WhatsAppSenderResponse.fromJson(_whatsappSender('snd_real', '+18095551234', 'Taxi Caribe')),
      ];

  @override
  Future<List<TenantJwtSecretResponse>> listTenantJwtSecrets(String tenantId) async => const [];

  @override
  Future<List<TenantOidcIssuerResponse>> listTenantOidcIssuers(String tenantId) async => const [];

  @override
  Future<List<ToolResponse>> listTools({String tenant = 'default'}) async => const [];
}

Widget _screen(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: SystemSendersScreen())),
    );

const _simulatorLabel = 'Playground simulator  ·  messages appear in the Playground';

void main() {
  setUp(() {
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('the workspace Playground simulator is offered as a WhatsApp number', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi(null)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Chat only'));
    await tester.pumpAndSettle();
    expect(find.text(_simulatorLabel), findsWidgets);
    expect(find.textContaining('Taxi Caribe'), findsWidgets);
    // The simulator row itself is not listed a second time as a plain number.
    expect(find.textContaining('+15550100'), findsNothing);

    await tester.tap(find.text(_simulatorLabel).last);
    await tester.pumpAndSettle();
    expect(find.textContaining('Messages appear in the Playground'), findsOneWidget);
  });

  testWidgets('a sender linked to the simulator is shown as linked, not as an error', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi(_simulator)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ride updates'));
    await tester.pumpAndSettle();
    final edit = find.widgetWithText(FilledButton, 'Clone & edit');
    await tester.ensureVisible(edit);
    await tester.tap(edit);
    await tester.pumpAndSettle();

    expect(find.text(_simulatorLabel), findsOneWidget);
    expect(find.textContaining('Messages appear in the Playground'), findsOneWidget);
    expect(find.textContaining('sends nothing'), findsNothing);
    expect(find.textContaining('(not found)'), findsNothing);
  });
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/system_senders/system_senders_screen.dart';
import 'package:yayaagenticweb/models/admin/chat_entry_point.dart';
import 'package:yayaagenticweb/models/admin/system_sender.dart';
import 'package:yayaagenticweb/models/admin/tool.dart';
import 'package:yayaagenticweb/models/admin/tenant_jwt_secret.dart';
import 'package:yayaagenticweb/models/admin/tenant_oidc_issuer.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';
import 'package:yayaagenticweb/ui/form/y_resource_picker.dart';

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
  SystemSenderRequest? created;

  @override
  Future<SystemSenderResponse> createSystemSender(String tenantId, SystemSenderRequest body) async {
    created = body;
    return SystemSenderResponse.fromJson({'id': 'sys_new', 'tenantId': tenantId, ...body.toJson()});
  }

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

Future<void> _openWhatsAppPicker(WidgetTester tester) async {
  final field = find.descendant(
      of: find.widgetWithText(YResourcePicker, 'WhatsApp number  Optional'), matching: find.byType(TextField));
  await tester.ensureVisible(field);
  await tester.tap(field);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('the workspace Playground simulator is offered as a WhatsApp number', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi(null)));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'New system sender'));
    await tester.pumpAndSettle();

    await _openWhatsAppPicker(tester);
    expect(find.text('Playground simulator'), findsWidgets);
    expect(find.textContaining('Taxi Caribe'), findsWidgets);
    // The simulator row itself is not listed a second time as a plain number.
    expect(find.textContaining('+15550100'), findsNothing);

    await tester.tap(find.text('Playground simulator').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('Messages appear in the Playground'), findsOneWidget);
  });

  testWidgets('a sender linked to the simulator is shown as linked, not as an error', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi(_simulator)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ride updates'));
    await tester.pumpAndSettle();

    expect(find.text('Playground simulator ($_simulator)'), findsOneWidget);
    expect(find.textContaining('Messages appear in the Playground'), findsOneWidget);
    expect(find.textContaining('$_simulator” doesn’t exist'), findsNothing);
    expect(find.textContaining('(not found)'), findsNothing);
  });

  testWidgets('creating a sender without a name or chat shows the problems at the fields', (tester) async {
    final api = _FakeAdminApi(null);
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'New system sender'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Create sender'));
    await tester.pumpAndSettle();

    expect(find.text('Enter the name users see.'), findsOneWidget);
    expect(find.text('Choose the chat this sender posts into.'), findsOneWidget);
    expect(find.textContaining('2 things need attention'), findsOneWidget);
    expect(api.created, isNull);
  });

  testWidgets('saving an edit keeps settings the form does not show', (tester) async {
    final api = _EditingApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Ride updates'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Save sender'));
    await tester.pumpAndSettle();

    final body = api.updated!.toJson();
    expect(body['capabilityMask'], {'allowMedia': false});
    expect(body['limits'], {'perMinute': 5});
    expect((body['addressing'] as Map)['allowedRoles'], ['cashier']);
    expect((body['addressing'] as Map)['allowedKinds'], ['PRINCIPAL', 'ROLE']);
  });
}

class _EditingApi extends _FakeAdminApi {
  _EditingApi() : super(null);
  SystemSenderRequest? updated;

  @override
  Future<List<SystemSenderResponse>> listSystemSenders(String tenantId) async => [
        SystemSenderResponse.fromJson({
          'id': 'sys_1',
          'tenantId': 't1',
          'entryPoint': 'ep_1',
          'displayName': 'Ride updates',
          'enabled': true,
          'trust': {'authenticatorName': 'shared-secret-jwt', 'credentialRef': ''},
          'mediation': 'VERBATIM',
          'addressing': {'allowedKinds': ['PRINCIPAL', 'ROLE'], 'allowedRoles': ['cashier']},
          'capabilityMask': {'allowMedia': false},
          'limits': {'perMinute': 5},
        }),
      ];

  @override
  Future<SystemSenderResponse> updateSystemSender(String tenantId, String senderId, SystemSenderRequest body) async {
    updated = body;
    return (await listSystemSenders(tenantId)).first;
  }
}

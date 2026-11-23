import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/chats/chats_admin_screen.dart';
import 'package:yayaagenticweb/models/admin/auth_resolver.dart';
import 'package:yayaagenticweb/models/admin/chat_entry_point.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/start_flow.dart';

Map<String, dynamic> _entryPoint({
  String id = 'chat_1',
  String name = 'Market',
  String status = 'ACTIVE',
  Map<String, dynamic> authConfig = const {'kind': 'None'},
  String authMode = 'ANONYMOUS_ONLY',
  bool verifiedPhone = false,
}) =>
    {
      'id': id,
      'tenantId': 't1',
      'handle': 'e_abc',
      'deepLink': 'https://chat.example/e/e_abc',
      'profileId': 'concierge',
      'profileVersion': 2,
      'displayName': name,
      'logoUrl': 'https://example.invalid/logo.png',
      'accentColorHex': '#2563EB',
      'welcomeText': 'Hi there',
      'defaultLocale': 'es-DO',
      'tagline': 'Fresh food',
      'discoverable': true,
      'authMode': authMode,
      'authConfig': authConfig,
      'abuse': {'startsPerDevicePerHour': 3, 'challenge': 'TURNSTILE'},
      'requiresVerifiedPhone': verifiedPhone,
      'status': status,
      'allowedOverrideProfiles': ['vip'],
      'onOverrideDenied': 'REJECT',
      'startFlowId': 'welcome',
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.entryPoints) : super(Dio());

  final List<Map<String, dynamic>> entryPoints;
  final created = <ChatEntryPointRequest>[];
  final updated = <ChatEntryPointRequest>[];
  int rotated = 0;

  @override
  Future<List<ChatEntryPointResponse>> listChatEntryPoints(String tenantId) async =>
      [for (final e in entryPoints) ChatEntryPointResponse.fromJson(e)];

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        ProfileResponse(id: 'concierge', version: 2, tenant: tenant, displayName: 'Concierge', introOneLiner: '', systemPromptFragment: ''),
        ProfileResponse(id: 'vip', version: 1, tenant: tenant, displayName: 'VIP', introOneLiner: '', systemPromptFragment: ''),
      ];

  @override
  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async => [
        StartFlowResponse.fromJson({'id': 'welcome', 'version': 1, 'tenantId': tenantId, 'name': 'Welcome', 'status': 'ACTIVE'}),
      ];

  @override
  Future<AuthResolverListResponse> listAuthResolvers(String tenantId) async => AuthResolverListResponse.fromJson({
        'resolvers': [
          {'id': 7, 'tenantId': tenantId, 'name': 'Acme accounts', 'kind': 'HTTP', 'status': 'ACTIVE'},
        ],
        'registeredBeanNames': const <String>[],
      });

  @override
  Future<ChatEntryPointResponse> createChatEntryPoint(String tenantId, ChatEntryPointRequest body) async {
    created.add(body);
    return ChatEntryPointResponse.fromJson(_entryPoint(status: 'DRAFT'));
  }

  @override
  Future<ChatEntryPointResponse> updateChatEntryPoint(String tenantId, String id, ChatEntryPointRequest body) async {
    updated.add(body);
    return ChatEntryPointResponse.fromJson(entryPoints.first);
  }

  @override
  Future<ChatEntryPointResponse> rotateChatEntryPointHandle(String tenantId, String id) async {
    rotated++;
    return ChatEntryPointResponse.fromJson({...entryPoints.first, 'handle': 'e_new', 'deepLink': 'https://chat.example/e/e_new'});
  }
}

Widget _app(_FakeAdminApi api, {Key? key}) {
  final router = GoRouter(
    initialLocation: '/admin/chats',
    routes: [GoRoute(path: '/admin/chats', builder: (_, _) => const Scaffold(body: ChatsAdminScreen()))],
  );
  return ProviderScope(
    key: key,
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

/// The editor on its own route. showYSheet's frame trips a semantics assertion
/// under the test binding (Semantics(scopesRoute) without explicitChildNodes),
/// so editor tests mount the sheet body directly.
Future<void> _openEditor(WidgetTester tester, _FakeAdminApi api, {Map<String, dynamic>? existing}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: FilledButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => Scaffold(
                  body: ChatEntryPointSheet(
                    tenant: 't1',
                    existing: existing == null ? null : ChatEntryPointResponse.fromJson(existing),
                  ),
                ),
              )),
              child: const Text('open editor'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('open editor'));
  await tester.pumpAndSettle();
}

Future<void> _tapVisible(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('lists chats with a status word, not a raw enum, and the empty state offers to create one', (tester) async {
    await tester.pumpWidget(_app(_FakeAdminApi([_entryPoint(), _entryPoint(id: 'chat_2', name: 'Bakery', status: 'DRAFT')])));
    await tester.pumpAndSettle();

    expect(find.text('Market'), findsOneWidget);
    expect(find.text('Bakery'), findsOneWidget);
    expect(find.text('Live'), findsWidgets);
    expect(find.text('Draft'), findsWidgets);
    expect(find.text('ACTIVE'), findsNothing);

    await tester.pumpWidget(_app(_FakeAdminApi([]), key: UniqueKey()));
    await tester.pumpAndSettle();
    expect(find.text('Create your first chat'), findsOneWidget);
  });

  testWidgets('creating a chat with nothing filled in shows the problems at the fields and sends nothing', (tester) async {
    final api = _FakeAdminApi([]);
    await _openEditor(tester, api);
    await _tapVisible(tester, find.widgetWithText(FilledButton, 'Create draft'));

    expect(find.text('Choose the profile people talk to in this chat.'), findsWidgets);
    expect(find.text('Enter the name people see on the tile.'), findsWidgets);
    expect(find.text('Upload a logo — it’s the tile people tap.'), findsWidgets);
    expect(api.created, isEmpty);

    // Asking for sign-in through OIDC needs an issuer to send people to.
    await _tapVisible(tester, find.text('Sign-in required'));
    await _tapVisible(tester, find.widgetWithText(FilledButton, 'Create draft'));
    expect(find.text('Enter a URL.'), findsWidgets);
    expect(api.created, isEmpty);
  });

  testWidgets('saving an untouched resolver chat keeps what the form does not edit', (tester) async {
    final api = _FakeAdminApi([
      _entryPoint(
        authMode: 'AUTH_REQUIRED',
        authConfig: {'kind': 'ResolverExchange', 'authResolverId': 7, 'senderKey': 'F0DX-12FF'},
        verifiedPhone: true,
      ),
    ]);
    await _openEditor(tester, api, existing: api.entryPoints.first);
    expect(find.text('F0DX-12FF'), findsOneWidget);
    await _tapVisible(tester, find.widgetWithText(FilledButton, 'Save changes'));

    expect(api.updated, hasLength(1));
    final json = api.updated.single.toUpdateJson();
    expect(json['profileId'], 'concierge');
    expect(json['profileVersion'], 2);
    expect(json['authMode'], 'AUTH_REQUIRED');
    expect(json['authConfig'], {'kind': 'ResolverExchange', 'authResolverId': 7, 'senderKey': 'F0DX-12FF'});
    expect(json['requiresVerifiedPhone'], isTrue);
    expect(json['allowedOverrideProfiles'], ['vip']);
    expect(json['onOverrideDenied'], 'REJECT');
    expect(json['startFlowId'], 'welcome');
    expect(json['accentColorHex'], '#2563EB');
    expect(json['defaultLocale'], 'es-DO');
    expect(json['discoverable'], isTrue);
    // Not edited here: omitted so the server keeps the stored abuse policy.
    expect(json.containsKey('abuse'), isFalse);
  });

  testWidgets('saving an OIDC chat keeps its scopes', (tester) async {
    final api = _FakeAdminApi([
      _entryPoint(
        authMode: 'AUTH_OPTIONAL',
        authConfig: {'kind': 'Oidc', 'issuer': 'https://login.acme.com', 'scopes': ['openid', 'orders']},
      ),
    ]);
    await _openEditor(tester, api, existing: api.entryPoints.first);
    await _tapVisible(tester, find.widgetWithText(FilledButton, 'Save changes'));

    final auth = api.updated.single.toUpdateJson()['authConfig'] as Map;
    expect(auth['issuer'], 'https://login.acme.com');
    expect(auth['scopes'], ['openid', 'orders']);
  });

  testWidgets('replacing the link asks first and says the old QR stops working', (tester) async {
    final api = _FakeAdminApi([_entryPoint()]);
    await _openEditor(tester, api, existing: api.entryPoints.first);
    await _tapVisible(tester, find.widgetWithText(TextButton, 'Replace link'));
    expect(find.textContaining('stop working immediately'), findsOneWidget);
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.widgetWithText(OutlinedButton, 'Cancel')));
    await tester.pumpAndSettle();
    expect(api.rotated, 0);

    await _tapVisible(tester, find.widgetWithText(TextButton, 'Replace link'));
    await tester.tap(find.widgetWithText(FilledButton, 'Replace link'));
    await tester.pumpAndSettle();
    expect(api.rotated, 1);
    expect(find.text('https://chat.example/e/e_new'), findsOneWidget);
  });
}

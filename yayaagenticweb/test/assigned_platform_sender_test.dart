import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/assigned_platform_sender_section.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/whatsapp_senders_screen.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';
import 'package:yayaagenticweb/models/admin/auth_resolver.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/platform_sender.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_embedded_signup.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

DioException _apiError(int status, String code, String message) {
  final options = RequestOptions(path: '/v1/admin/tenants/t1/whatsapp-senders/snd_p/keyword-actions');
  return DioException(
    requestOptions: options,
    response: Response(requestOptions: options, statusCode: status, data: {'error': code, 'message': message}),
  );
}

/// `GET …/whatsapp-senders` as an assigned workspace sees a platform sender (§17.1).
Map<String, dynamic> _platformSenderJson() => {
      'id': 'snd_p',
      'tenantId': null,
      'owner': 'PLATFORM',
      'phoneE164': '+15024874573',
      'displayName': 'Avisos',
      'approvedDisplayName': 'Avisos Smart Lottery',
      'businessProfile': {'legalName': 'SL', 'displayName': 'SL', 'contactEmail': 'a@b.co', 'vertical': 'OTHER'},
      'status': 'ACTIVE',
      'onboarding': {'status': 'ONLINE', 'twilioSenderSid': 'XE1'},
      'defaultLocale': 'es-DO',
      'assignment': {
        'id': 'wsa_1',
        'label': 'Banca La Suerte',
        'status': 'ACTIVE',
        'dailyOutboundCap': 1000,
        'nonMemberReply': 'Gracias por escribir.',
        'templateSends24h': 12,
        'members': 2,
      },
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  List<KeywordActionItem> keywords = const [
    KeywordActionItem(prefix: 'TICKET', forwardUrl: 'https://hub.example/t', scope: KeywordScope.routed),
  ];
  List<KeywordActionItem>? replaced;
  String? takenPrefix;
  String? savedNonMemberReply;
  ChannelBindingRequest? createdBinding;

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async =>
      [WhatsAppSenderResponse.fromJson(_platformSenderJson())];

  @override
  Future<bool> isWhatsAppSubmitEnabled(String tenantId) async => true;

  @override
  Future<EmbeddedSignupConfig> getWhatsAppEmbeddedSignupConfig(String tenantId) async =>
      EmbeddedSignupConfig.disabled();

  @override
  Future<List<ChannelBindingResponse>> listChannelBindings(String tenantId) async => const [];

  @override
  Future<ChannelBindingResponse> createChannelBinding(String tenantId, ChannelBindingRequest body) async {
    createdBinding = body;
    return ChannelBindingResponse.fromJson({...body.toJson(), 'id': 1, 'tenantId': tenantId});
  }

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        ProfileResponse(id: 'staff', version: 1, tenant: tenant, displayName: 'Staff', introOneLiner: '', systemPromptFragment: ''),
      ];

  @override
  Future<AuthResolverListResponse> listAuthResolvers(String tenantId) async => AuthResolverListResponse.fromJson({
        'resolvers': [
          {'id': 5, 'tenantId': tenantId, 'name': 'staff-lookup', 'kind': 'HTTP', 'status': 'ACTIVE'},
        ],
        'registeredBeanNames': const [],
      });

  @override
  Future<SenderMembers> listSenderMembers(String tenantId, String senderId) async =>
      const SenderMembers(phones: ['+18095550001', '+18095550002'], count: 2);

  @override
  Future<List<KeywordActionItem>> listKeywordActions(String tenantId, String senderId) async => keywords;

  @override
  Future<List<KeywordActionItem>> replaceKeywordActions(
      String tenantId, String senderId, List<KeywordActionItem> actions) async {
    replaced = actions;
    if (takenPrefix != null) throw _apiError(409, 'keyword_prefix_taken', 'prefix $takenPrefix is taken');
    keywords = actions;
    return actions;
  }

  @override
  Future<SenderAssignmentView> updateSenderAssignment(String tenantId, String senderId,
      {required String? nonMemberReply}) async {
    savedNonMemberReply = nonMemberReply;
    return SenderAssignmentView.fromJson({..._platformSenderJson()['assignment'] as Map<String, dynamic>, 'nonMemberReply': nonMemberReply});
  }
}

class _SignedIn extends AuthController {
  _SignedIn({required this.platformAdmin});

  final bool platformAdmin;

  @override
  AuthState build() => AuthAuthenticated(Operator(
        subject: 'op-1',
        displayName: 'Op',
        source: 'user-password',
        platformAdmin: platformAdmin,
      ));
}

Widget _screen(_FakeAdminApi api, {bool platformAdmin = false}) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
        authProvider.overrideWith(() => _SignedIn(platformAdmin: platformAdmin)),
      ],
      child: const MaterialApp(home: Scaffold(body: WhatsAppSendersScreen())),
    );

Future<void> _open(WidgetTester tester) async {
  await tester.tap(find.text('Avisos Smart Lottery').first);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('an assigned platform sender is marked shared, with this workspace’s label', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi()));
    await tester.pumpAndSettle();
    expect(find.text('Platform · shared'), findsOneWidget);
    expect(find.text('as “Banca La Suerte”'), findsOneWidget);
  });

  testWidgets('its card is read-only for lifecycle, even for a platform admin', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi(), platformAdmin: true));
    await tester.pumpAndSettle();
    await _open(tester);

    for (final label in ['Edit', 'Submit for review', 'Enter code', 'Pause', 'Remove', 'Resume', 'Fix & resubmit']) {
      expect(find.widgetWithText(ButtonStyleButton, label), findsNothing, reason: label);
    }
    expect(find.byKey(const Key('sender-transfer')), findsNothing);
    expect(find.text('Move to workspace…'), findsNothing);

    // Read-only facts: label, cap, today's sends.
    expect(find.text('Banca La Suerte'), findsWidgets);
    expect(find.text('1000 per 24 h'), findsOneWidget);
    expect(find.text('12 of 1000'), findsOneWidget);

    // Members: read-only list + count.
    expect(find.descendant(of: find.byKey(const Key('members-count')), matching: find.text('2')), findsOneWidget);
    expect(find.text('+18095550001'), findsOneWidget);
    expect(find.text('+18095550002'), findsOneWidget);
  });

  testWidgets('the workspace edits its own non-member reply', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _open(tester);

    final field = find.byKey(const Key('non-member-reply'));
    await tester.ensureVisible(field);
    await tester.enterText(field, 'Gracias por escribir a Banca La Suerte.');
    await tester.pump();
    await tester.tap(find.byKey(const Key('non-member-reply-save')));
    await tester.pumpAndSettle();
    expect(api.savedNonMemberReply, 'Gracias por escribir a Banca La Suerte.');
  });

  testWidgets('keyword actions carry a scope; a taken prefix is flagged on its row', (tester) async {
    final api = _FakeAdminApi()..takenPrefix = 'TICKET';
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _open(tester);

    final scope = find.byKey(const ValueKey('keyword-scope-0'));
    await tester.ensureVisible(scope);
    expect(tester.widget<KeywordScopeSelector>(scope).value, KeywordScope.routed);

    // Add a REPLY keyword.
    await tester.tap(find.byKey(const Key('keyword-add')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('keyword-prefix-1')), 'NO ES MIO');
    await tester.enterText(find.byKey(const ValueKey('keyword-url-1')), 'https://hub.example/n');
    final second = find.byKey(const ValueKey('keyword-scope-1'));
    await tester.ensureVisible(second);
    await tester.tap(find.descendant(of: second, matching: find.text('Replies only')));
    await tester.pumpAndSettle();

    final save = find.byKey(const Key('keyword-save'));
    await tester.ensureVisible(save);
    await tester.tap(save);
    await tester.pumpAndSettle();

    expect(api.replaced!.map((a) => a.toJson()).toList(), [
      {'prefix': 'TICKET', 'forwardUrl': 'https://hub.example/t', 'scope': 'ROUTED'},
      {'prefix': 'NO ES MIO', 'forwardUrl': 'https://hub.example/n', 'scope': 'REPLY'},
    ]);
    expect(find.textContaining('“TICKET” already routes to another business on this number'), findsOneWidget);
  });

  testWidgets('reserved words are refused on a shared number before anything is sent', (tester) async {
    final api = _FakeAdminApi()..keywords = const [];
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _open(tester);

    final add = find.byKey(const Key('keyword-add'));
    await tester.ensureVisible(add);
    await tester.tap(add);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('keyword-prefix-0')), 'cambiar negocio');
    await tester.enterText(find.byKey(const ValueKey('keyword-url-0')), 'https://hub.example/x');
    final save = find.byKey(const Key('keyword-save'));
    await tester.ensureVisible(save);
    await tester.tap(save);
    await tester.pumpAndSettle();

    expect(api.replaced, isNull);
    expect(find.text('“cambiar negocio” is reserved on shared numbers.'), findsOneWidget);
  });

  testWidgets('a route on a shared number needs a resolver and always drops the unresolved', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _open(tester);

    final add = find.widgetWithText(TextButton, 'Add route');
    await tester.ensureVisible(add);
    await tester.tap(add);
    await tester.pumpAndSettle();
    expect(find.textContaining('Shared platform number.', findRichText: true), findsOneWidget);
    expect(find.text('None — FAQ mode'), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'Add route'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Choose an identity resolver'), findsWidgets);
    expect(api.createdBinding, isNull);
  });

  testWidgets('the scope selector reports the chosen scope', (tester) async {
    var scope = KeywordScope.routed;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) => KeywordScopeSelector(value: scope, onChanged: (v) => setState(() => scope = v)),
        ),
      ),
    ));
    await tester.tap(find.text('Replies only'));
    await tester.pumpAndSettle();
    expect(scope, KeywordScope.reply);
    await tester.tap(find.text('Routes by itself'));
    await tester.pumpAndSettle();
    expect(scope, KeywordScope.routed);
  });

  testWidgets('a disabled option card can’t be picked', (tester) async {
    var picked = 'A';
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: YEnumCards<String>(
          label: 'Pick',
          value: picked,
          onChanged: (v) => picked = v,
          options: const [YEnumOption('A', 'First'), YEnumOption('B', 'Second', enabled: false)],
        ),
      ),
    ));
    await tester.tap(find.text('Second'));
    await tester.pump();
    expect(picked, 'A');
  });
}

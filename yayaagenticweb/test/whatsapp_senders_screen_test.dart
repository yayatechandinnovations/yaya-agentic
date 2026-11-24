import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/whatsapp_senders_screen.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/tenant.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_embedded_signup.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

Map<String, dynamic> _senderJson(String status, String rawStatus,
        {String? failure, Map<String, dynamic> metadata = const {}}) => {
      'id': 'snd_test',
      'tenantId': 't1',
      'phoneE164': '+18095551234',
      'displayName': 'Test Co',
      'businessProfile': {
        'legalName': 'Test Inc.',
        'displayName': 'Test Co',
        'contactEmail': 'ops@test.example',
        'vertical': 'RESTAURANT',
      },
      'status': status,
      'onboarding': {
        'status': rawStatus,
        'twilioSenderSid': 'XE1',
        'failureMessage': ?failure,
      },
      'defaultLocale': 'es-DO',
      'metadata': metadata,
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.status, this.rawStatus) : super(Dio());

  String status;
  String rawStatus;
  String? failure;
  String? verifiedCode;
  String? submittedMethod;
  Map<String, dynamic> metadata = const {};
  final transfers = <({WhatsAppSenderTransferRequest body, bool dryRun})>[];
  bool repointed = false;

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async =>
      [WhatsAppSenderResponse.fromJson(
          _senderJson(status, rawStatus, failure: failure, metadata: metadata))];

  @override
  Future<List<TenantResponse>> listTenants({String? status}) async => const [
        TenantResponse(id: 't1', displayName: 'Here', status: 'ACTIVE'),
        TenantResponse(id: 't2', displayName: 'Bakery', status: 'ACTIVE'),
        TenantResponse(id: 't3', displayName: 'Closed', status: 'SUSPENDED'),
      ];

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        ProfileResponse(id: 'concierge', version: 1, tenant: tenant, displayName: 'Concierge',
            introOneLiner: '', systemPromptFragment: ''),
        ProfileResponse(id: 'concierge', version: 2, tenant: tenant, displayName: 'Concierge',
            introOneLiner: '', systemPromptFragment: ''),
      ];

  @override
  Future<WhatsAppSenderTransferResponse> transferWhatsAppSender(
      String tenantId, String senderId, WhatsAppSenderTransferRequest body,
      {bool dryRun = false}) async {
    transfers.add((body: body, dryRun: dryRun));
    return WhatsAppSenderTransferResponse.fromJson({
      'transferId': dryRun ? null : 'wst_1',
      'dryRun': dryRun,
      'source': _senderJson(dryRun ? status : 'REVOKED', rawStatus),
      if (!dryRun) 'destination': {..._senderJson('ACTIVE', rawStatus), 'tenantId': 't2'},
      'webhookRepoint': dryRun ? null : 'DONE',
      'effects': {'optOutsCopied': 3, 'openSessionsClosed': 1, 'bindingsDeactivated': 1},
      'warnings': ['Integrations in the source workspace lose their WhatsApp number: pos.'],
    });
  }

  @override
  Future<WhatsAppSenderResponse> repointWhatsAppSenderWebhooks(
      String tenantId, String senderId) async {
    repointed = true;
    metadata = {'transfer': {'fromTenant': 't0', 'webhooks': 'DONE'}};
    return WhatsAppSenderResponse.fromJson(_senderJson(status, rawStatus, metadata: metadata));
  }

  @override
  Future<bool> isWhatsAppSubmitEnabled(String tenantId) async => true;

  @override
  Future<EmbeddedSignupConfig> getWhatsAppEmbeddedSignupConfig(String tenantId) async =>
      EmbeddedSignupConfig.disabled();

  @override
  Future<List<ChannelBindingResponse>> listChannelBindings(String tenantId) async => const [];

  @override
  Future<WhatsAppSenderResponse> verifyWhatsAppSender(
      String tenantId, String senderId, String code) async {
    verifiedCode = code;
    rawStatus = 'VERIFYING';
    return WhatsAppSenderResponse.fromJson(_senderJson(status, rawStatus));
  }

  @override
  Future<WhatsAppSenderResponse> submitWhatsAppSender(String tenantId, String senderId,
      {String? verificationMethod}) async {
    submittedMethod = verificationMethod;
    status = 'PENDING_REVIEW';
    rawStatus = 'CREATING';
    return WhatsAppSenderResponse.fromJson(_senderJson(status, rawStatus));
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

/// Opens the sender's detail sheet from its table row.
Future<void> _expand(WidgetTester tester) async {
  await tester.tap(find.text('Test Co').first);
  await tester.pumpAndSettle();
}

/// The sheet reads the live list, so it reflects an action without reopening.
Future<void> _expandIfCollapsed(WidgetTester tester) async {}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('a sender waiting on a code takes it and sends it for verification',
      (tester) async {
    final api = _FakeAdminApi('PENDING_REVIEW', 'PENDING_VERIFICATION');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    // Visible on the collapsed card — the operator must not have to hunt for it.
    expect(find.text('Code needed'), findsOneWidget);
    expect(find.textContaining('Action needed'), findsOneWidget);

    await _expand(tester);
    expect(find.textContaining('Waiting for the verification code'), findsOneWidget);
    final enterCode = find.widgetWithText(FilledButton, 'Enter code');
    await tester.ensureVisible(enterCode);
    await tester.tap(enterCode);
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).last, '123456');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Verify'));
    await tester.pumpAndSettle();

    expect(api.verifiedCode, '123456');
    await _expandIfCollapsed(tester);
    expect(find.textContaining('Checking the verification code'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Enter code'), findsNothing);
  });

  testWidgets('submitting a draft carries the chosen verification method', (tester) async {
    final api = _FakeAdminApi('DRAFT', '');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    final submit = find.widgetWithText(FilledButton, 'Submit for review');
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Voice call'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Submit'));
    await tester.pumpAndSettle();

    expect(api.submittedMethod, 'voice');
    await _expandIfCollapsed(tester);
    expect(find.textContaining('Registering the number with WhatsApp'), findsOneWidget);
  });

  testWidgets('an in-flight sender offers cancellation instead of removal', (tester) async {
    final api = _FakeAdminApi('PENDING_REVIEW', 'TWILIO_REVIEW');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    expect(find.text('Cancel registration'), findsOneWidget);
    expect(find.text('Remove'), findsNothing);
    expect(find.textContaining('In final review'), findsOneWidget);
  });

  testWidgets('a rejected sender says why on the collapsed card', (tester) async {
    final api = _FakeAdminApi('FAILED', 'REJECTED')..failure = 'website must be a valid URL';
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    expect(find.textContaining('Rejected: website must be a valid URL'), findsOneWidget);

    await _expand(tester);
    expect(find.text('Why it was rejected'), findsOneWidget);
    expect(find.text('Fix & resubmit'), findsOneWidget);
  });

  testWidgets('a registration in progress takes a code before WhatsApp reports waiting for one',
      (tester) async {
    final api = _FakeAdminApi('PENDING_REVIEW', 'CREATING');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    expect(find.text('Got a code from WhatsApp? Open to enter it'), findsOneWidget);
    await _expand(tester);
    final enterCode = find.widgetWithText(FilledButton, 'Enter code');
    await tester.ensureVisible(enterCode);
    await tester.tap(enterCode);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).last, '654321');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Verify'));
    await tester.pumpAndSettle();

    expect(api.verifiedCode, '654321');
  });

  testWidgets('an offline registration shows the problem and still offers the code',
      (tester) async {
    final api = _FakeAdminApi('PENDING_REVIEW', 'OFFLINE')
      ..failure = 'Something went wrong. Please create a support ticket';
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    expect(find.text('WhatsApp reported a problem'), findsOneWidget);
    expect(find.textContaining('Something went wrong'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Enter code'), findsOneWidget);
    expect(find.text('Cancel registration'), findsOneWidget);
    expect(find.text('Resume'), findsNothing);
  });

  testWidgets('a workspace admin is not offered moving a number', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi('ACTIVE', 'ONLINE')));
    await tester.pumpAndSettle();
    await _expand(tester);
    expect(find.text('Move to workspace…'), findsNothing);
  });

  testWidgets('a platform admin is offered moving a live number', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi('ACTIVE', 'ONLINE'), platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);
    expect(find.text('Move to workspace…'), findsOneWidget);
  });

  testWidgets('a draft has nothing to move', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi('DRAFT', ''), platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);
    expect(find.text('Move to workspace…'), findsNothing);
  });

  testWidgets('moving a number previews the effects, then applies what was previewed',
      (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE');
    await tester.pumpWidget(_screen(api, platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);

    final move = find.byKey(const Key('sender-transfer'));
    await tester.ensureVisible(move);
    await tester.tap(move);
    await tester.pumpAndSettle();

    // Only other, active workspaces are destinations.
    await tester.tap(find.byKey(const Key('transfer-destination')));
    await tester.pumpAndSettle();
    expect(find.text('Here (t1)'), findsNothing);
    expect(find.text('Closed (t3)'), findsNothing);
    await tester.tap(find.text('Bakery (t2)').last);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('transfer-assistant-t2')));
    await tester.pumpAndSettle();
    // One entry per assistant, whatever its versions.
    expect(find.text('Concierge'), findsOneWidget);
    await tester.tap(find.text('Concierge').last);
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Preview'));
    await tester.pumpAndSettle();
    expect(api.transfers.single.dryRun, isTrue);
    expect(find.byKey(const Key('transfer-plan')), findsOneWidget);
    expect(find.textContaining('3 opt-outs carried over'), findsOneWidget);
    expect(find.textContaining('lose their WhatsApp number'), findsOneWidget);

    await tester.tap(find.byKey(const Key('transfer-apply')));
    await tester.pumpAndSettle();

    final applied = api.transfers.last;
    expect(applied.dryRun, isFalse);
    expect(applied.body.destinationTenant, 't2');
    expect(applied.body.profileId, 'concierge');
    expect(applied.body.copyOptOuts, isTrue);
    expect(find.text('Moved to t2.'), findsOneWidget);
  });

  testWidgets('a platform admin can move a number in as a shared platform sender', (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE');
    await tester.pumpWidget(_screen(api, platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);
    final move = find.byKey(const Key('sender-transfer'));
    await tester.ensureVisible(move);
    await tester.tap(move);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('transfer-destination')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Platform (shared)').last);
    await tester.pumpAndSettle();
    // No assistant and no opt-out choice: the number is shared, opt-outs are the number's.
    expect(find.byKey(const Key('transfer-assistant-__platform__')), findsNothing);
    expect(find.byKey(const Key('transfer-copy-opt-outs')), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'Preview'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('transfer-apply')));
    await tester.pumpAndSettle();

    expect(api.transfers, hasLength(2));
    for (final t in api.transfers) {
      expect(t.body.toJson(), {'destinationPlatform': true, 'copyOptOuts': true});
    }
    expect(api.transfers.first.dryRun, isTrue);
    expect(api.transfers.last.dryRun, isFalse);
  });

  testWidgets('changing the move after a preview asks for a new preview', (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE');
    await tester.pumpWidget(_screen(api, platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);
    final move = find.byKey(const Key('sender-transfer'));
    await tester.ensureVisible(move);
    await tester.tap(move);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('transfer-destination')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Bakery (t2)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Preview'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('transfer-apply')), findsOneWidget);

    await tester.tap(find.byKey(const Key('transfer-copy-opt-outs')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('transfer-apply')), findsNothing);
    expect(find.widgetWithText(FilledButton, 'Preview'), findsOneWidget);
  });

  testWidgets('a moved number still being re-pointed says so and can be retried',
      (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE')
      ..metadata = {'transfer': {'fromTenant': 't0', 'webhooks': 'FAILED'}};
    await tester.pumpWidget(_screen(api, platformAdmin: true));
    await tester.pumpAndSettle();
    await _expand(tester);

    expect(find.text('Still finishing the move'), findsOneWidget);
    expect(find.text('t0'), findsOneWidget);
    final retry = find.text('Retry move');
    await tester.ensureVisible(retry);
    await tester.tap(retry);
    await tester.pumpAndSettle();

    expect(api.repointed, isTrue);
    await _expandIfCollapsed(tester);
    expect(find.text('Still finishing the move'), findsNothing);
  });

  testWidgets('a removal WhatsApp never confirmed can be finished', (tester) async {
    final api = _FakeAdminApi('REVOKED', 'ONLINE')..metadata = {'bspDeleteFailed': true};
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    expect(find.text('Removal not finished'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Finish removal'), findsOneWidget);
    expect(find.text('Remove'), findsNothing);
  });

  testWidgets('a verification code that is not 4–8 digits is flagged at the field and not sent',
      (tester) async {
    final api = _FakeAdminApi('PENDING_REVIEW', 'PENDING_VERIFICATION');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);
    final enterCode = find.widgetWithText(FilledButton, 'Enter code');
    await tester.ensureVisible(enterCode);
    await tester.tap(enterCode);
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Verify'));
    await tester.pumpAndSettle();
    expect(find.text('Enter the code WhatsApp sent.'), findsOneWidget);

    await tester.enterText(find.byType(TextField).last, '12');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Verify'));
    await tester.pumpAndSettle();
    expect(find.text('Codes are 4 to 8 digits.'), findsOneWidget);
    expect(api.verifiedCode, isNull);
  });

  testWidgets('a route needs a profile before it can be added', (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    final add = find.widgetWithText(TextButton, 'Add route');
    await tester.ensureVisible(add);
    await tester.tap(add);
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Add route'));
    await tester.pumpAndSettle();
    expect(find.text('Choose the profile that answers.'), findsOneWidget);
    expect(find.textContaining('need attention before saving this route'), findsNothing);
    expect(find.textContaining('needs attention before saving this route'), findsOneWidget);
  });

  testWidgets('removing a live number asks for the number to be typed', (tester) async {
    final api = _FakeAdminApi('ACTIVE', 'ONLINE');
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _expand(tester);

    final remove = find.widgetWithText(TextButton, 'Remove');
    await tester.ensureVisible(remove);
    await tester.tap(remove);
    await tester.pumpAndSettle();

    expect(find.text('Remove +18095551234?'), findsOneWidget);
    final confirm = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Remove sender'));
    expect(confirm.onPressed, isNull);
  });
}

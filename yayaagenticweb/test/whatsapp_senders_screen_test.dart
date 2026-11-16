import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/whatsapp_senders_screen.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_embedded_signup.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

Map<String, dynamic> _senderJson(String status, String rawStatus, {String? failure}) => {
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
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.status, this.rawStatus) : super(Dio());

  String status;
  String rawStatus;
  String? failure;
  String? verifiedCode;
  String? submittedMethod;

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async =>
      [WhatsAppSenderResponse.fromJson(_senderJson(status, rawStatus, failure: failure))];

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

Widget _screen(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: WhatsAppSendersScreen())),
    );

Future<void> _expand(WidgetTester tester) async {
  await tester.tap(find.text('Test Co').first);
  await tester.pumpAndSettle();
}

/// The list refetches after an action, which can rebuild the card collapsed.
Future<void> _expandIfCollapsed(WidgetTester tester) async {
  if (find.byIcon(Icons.expand_more).evaluate().isNotEmpty) await _expand(tester);
}

void main() {
  setUp(() {
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
}

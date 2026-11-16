import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/embedded_signup/embedded_signup_events.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/embedded_signup/signup_launcher.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/whatsapp_connect_wizard.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_embedded_signup.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

class _FakeLauncher implements EmbeddedSignupLauncher {
  final _controller = StreamController<EmbeddedSignupEvent>.broadcast();
  int loadCalls = 0;
  int launchCalls = 0;
  bool disposed = false;
  String? lastAppId;
  String? lastConfigurationId;
  String? lastSolutionId;
  Completer<void>? loadGate;

  @override
  Future<void> load({required String appId}) async {
    loadCalls++;
    lastAppId = appId;
    if (loadGate != null) await loadGate!.future;
  }

  @override
  void launch({required String configurationId, String? partnerSolutionId}) {
    launchCalls++;
    lastConfigurationId = configurationId;
    lastSolutionId = partnerSolutionId;
  }

  @override
  Stream<EmbeddedSignupEvent> get events => _controller.stream;

  void emit(EmbeddedSignupEvent e) => _controller.add(e);

  @override
  void dispose() {
    disposed = true;
    _controller.close();
  }
}

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  int startCalls = 0;
  int syncCalls = 0;
  OnboardingRequest? lastRequest;
  String attemptState = 'COMPLETED';
  String senderStatus = 'PENDING_REVIEW';
  Duration startDelay = Duration.zero;

  Map<String, dynamic> _senderJson(String status) => {
        'id': 'snd_test',
        'tenantId': 't1',
        'phoneE164': '+18095551234',
        'displayName': 'Test Co',
        'businessProfile': {
          'legalName': 'Test Inc.',
          'displayName': 'Test Co',
          'contactEmail': 'ops@test.example',
          'vertical': 'RETAIL',
        },
        'status': status,
        'onboarding': {'status': 'CREATING'},
        'defaultLocale': 'en-US',
        'wabaId': '123456789012345',
      };

  @override
  Future<OnboardingResponse> startWhatsAppEmbeddedSignup(
      String tenantId, OnboardingRequest body) async {
    startCalls++;
    lastRequest = body;
    if (startDelay > Duration.zero) {
      await Future<void>.delayed(startDelay);
    }
    return OnboardingResponse(
      attempt: OnboardingAttemptView.fromJson({
        'id': 'woa_test',
        'phoneE164': body.phoneE164,
        'wabaId': body.wabaId,
        'senderId': 'snd_test',
        'state': attemptState,
        if (attemptState == 'FAILED')
          'failureMessage': 'The messaging provider rejected the sender.',
      }),
      sender: WhatsAppSenderResponse.fromJson(_senderJson(senderStatus)),
    );
  }

  @override
  Future<WhatsAppSenderResponse> syncWhatsAppSender(
      String tenantId, String senderId) async {
    syncCalls++;
    return WhatsAppSenderResponse.fromJson(_senderJson('ACTIVE'));
  }

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(
          String tenantId) async =>
      const [];
}

void main() {
  late _FakeLauncher launcher;
  late _FakeAdminApi api;

  setUp(() {
    launcher = _FakeLauncher();
    api = _FakeAdminApi();
  });

  Widget wizard() {
    return ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: MaterialApp(
        home: WhatsAppConnectWizard(
          config: EmbeddedSignupConfig(
            enabled: true,
            metaAppId: 'app-1',
            metaConfigurationId: 'config-1',
            partnerSolutionId: 'solution-1',
          ),
          launcherFactory: () => launcher,
        ),
      ),
    );
  }

  Future<void> pumpWizard(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1400, 2800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(wizard());
    await tester.pumpAndSettle();
  }

  Future<void> enterByLabel(WidgetTester tester, String label, String text) async {
    final finder = find.byWidgetPredicate((w) =>
        w is TextField && w.decoration?.labelText == label);
    await tester.ensureVisible(finder);
    await tester.enterText(finder, text);
  }

  Future<void> fillDetailsStep(WidgetTester tester) async {
    await enterByLabel(tester, 'WhatsApp phone number (E.164) *', '+18095551234');
    await enterByLabel(tester, 'WhatsApp display name *', 'Test Co');
    await enterByLabel(tester, 'Business name *', 'Test Inc.');
    await enterByLabel(tester, 'Contact email *', 'ops@test.example');

    final dropdown = find.byType(DropdownButtonFormField<String>);
    await tester.ensureVisible(dropdown);
    await tester.tap(dropdown);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Shopping and retail').last);
    await tester.pumpAndSettle();

    final consent = find.byType(CheckboxListTile);
    await tester.ensureVisible(consent);
    await tester.tap(consent);
    await tester.pump();
  }

  Future<void> goToConnectStep(WidgetTester tester) async {
    await fillDetailsStep(tester);
    final next = find.widgetWithText(FilledButton, 'Next');
    await tester.ensureVisible(next);
    await tester.pump();
    await tester.tap(next);
    await tester.pumpAndSettle();
  }

  Future<void> tapButton(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pump();
    await tester.tap(finder);
  }

  testWidgets('step 1 validates before advancing', (tester) async {
    await pumpWizard(tester);

    await tapButton(tester, find.widgetWithText(FilledButton, 'Next'));
    await tester.pump();
    expect(find.textContaining('phone number is required'), findsOneWidget);
    expect(launcher.launchCalls, 0);
  });

  testWidgets('connect launches the Meta popup with the platform config',
      (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);

    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    expect(launcher.loadCalls, 1);
    expect(launcher.lastAppId, 'app-1');
    expect(launcher.launchCalls, 1);
    expect(launcher.lastConfigurationId, 'config-1');
    expect(launcher.lastSolutionId, 'solution-1');
  });

  testWidgets('duplicate clicks while waiting launch only once', (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);

    launcher.loadGate = Completer<void>(); // keep "connecting" pending
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();

    // Button is disabled and shows the waiting state.
    expect(find.text('Waiting for Meta…'), findsOneWidget);
    final button = tester.widget<FilledButton>(
        find.ancestor(of: find.text('Waiting for Meta…'), matching: find.byType(FilledButton)));
    expect(button.onPressed, isNull);

    launcher.loadGate!.complete();
    await tester.pump();
    await tester.pump();
    expect(launcher.loadCalls, 1);
    expect(launcher.launchCalls, 1);
  });

  testWidgets('FINISH submits to the backend and shows the async status',
      (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    launcher.emit(const SignupFinish(
        wabaId: '123456789012345', phoneNumberId: '999888777', wabaOnly: false));
    await tester.pumpAndSettle();

    expect(api.startCalls, 1);
    expect(api.lastRequest!.wabaId, '123456789012345');
    expect(api.lastRequest!.metaPhoneNumberId, '999888777');
    expect(api.lastRequest!.phoneE164, '+18095551234');
    // Status comes from the backend, not from the popup: PENDING_REVIEW.
    expect(find.text('Awaiting WhatsApp review'), findsOneWidget);
  });

  testWidgets('FINISH_ONLY_WABA submits without a Meta phone-number id',
      (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    launcher.emit(const SignupFinish(
        wabaId: '123456789012345', phoneNumberId: null, wabaOnly: true));
    await tester.pumpAndSettle();

    expect(api.startCalls, 1);
    expect(api.lastRequest!.metaPhoneNumberId, isNull);
  });

  testWidgets('CANCEL re-enables the connect button', (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    launcher.emit(const SignupCancel(currentStep: 'BUSINESS_INFO'));
    await tester.pumpAndSettle();

    expect(find.textContaining('cancelled'), findsOneWidget);
    expect(api.startCalls, 0);
    final button = tester.widget<FilledButton>(find.ancestor(
        of: find.text('Connect with Meta'), matching: find.byType(FilledButton)));
    expect(button.onPressed, isNotNull);
  });

  testWidgets('ERROR surfaces the Meta message and allows retry', (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    launcher.emit(const SignupError(message: 'session expired'));
    await tester.pumpAndSettle();

    expect(find.textContaining('session expired'), findsOneWidget);
    expect(api.startCalls, 0);
  });

  testWidgets('failed onboarding shows sanitized reason and a retry action',
      (tester) async {
    api.attemptState = 'FAILED';
    api.senderStatus = 'FAILED';
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();
    launcher.emit(const SignupFinish(
        wabaId: '123456789012345', phoneNumberId: null, wabaOnly: false));
    await tester.pumpAndSettle();

    expect(find.textContaining('rejected the sender'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Retry connection'), findsOneWidget);
  });

  testWidgets('refresh pulls the latest backend status', (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();
    launcher.emit(const SignupFinish(
        wabaId: '123456789012345', phoneNumberId: null, wabaOnly: false));
    await tester.pumpAndSettle();

    await tapButton(tester, find.widgetWithText(OutlinedButton, 'Refresh status'));
    await tester.pumpAndSettle();

    expect(api.syncCalls, 1);
    expect(find.text('Active'), findsOneWidget);
  });

  testWidgets('launcher is disposed with the page', (tester) async {
    await pumpWizard(tester);
    await goToConnectStep(tester);
    await tapButton(tester, find.widgetWithText(FilledButton, 'Connect with Meta'));
    await tester.pump();
    await tester.pump();

    await tester.pumpWidget(const SizedBox.shrink());
    expect(launcher.disposed, isTrue);
  });
}

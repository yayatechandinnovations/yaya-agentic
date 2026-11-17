import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/app/theme.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/whatsapp_sender_wizard.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/start_flow.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  WhatsAppSenderRequest? created;
  WhatsAppSenderRequest? updated;
  ChannelBindingRequest? binding;
  String? submittedSenderId;
  String? submittedMethod;
  bool failSubmit = false;

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        ProfileResponse.fromJson({
          'id': 'support',
          'version': 3,
          'tenant': 't1',
          'displayName': 'Support Bot',
          'introOneLiner': '',
          'systemPromptFragment': '',
        }),
      ];

  @override
  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async => const [];

  @override
  Future<bool> isWhatsAppSubmitEnabled(String tenantId) async => true;

  @override
  Future<WhatsAppSenderResponse> createWhatsAppSender(
      String tenantId, WhatsAppSenderRequest body) async {
    created = body;
    return WhatsAppSenderResponse.fromJson({
      'id': 'snd_new',
      'tenantId': tenantId,
      'phoneE164': body.phoneE164,
      'displayName': body.displayName,
      'businessProfile': body.businessProfile.toJson(),
      'status': 'DRAFT',
      'onboarding': const <String, dynamic>{},
      'defaultLocale': body.defaultLocale ?? 'en-US',
    });
  }

  @override
  Future<WhatsAppSenderResponse> updateWhatsAppSender(
      String tenantId, String senderId, WhatsAppSenderRequest body) async {
    updated = body;
    return WhatsAppSenderResponse.fromJson({
      'id': senderId,
      'tenantId': tenantId,
      'phoneE164': body.phoneE164,
      'displayName': body.displayName,
      'businessProfile': body.businessProfile.toJson(),
      'status': 'DRAFT',
      'onboarding': const <String, dynamic>{},
      'defaultLocale': body.defaultLocale ?? 'en-US',
    });
  }

  @override
  Future<ChannelBindingResponse> createChannelBinding(
      String tenantId, ChannelBindingRequest body) async {
    binding = body;
    return ChannelBindingResponse.fromJson({...body.toJson(), 'id': 1, 'tenantId': tenantId});
  }

  @override
  Future<WhatsAppSenderResponse> submitWhatsAppSender(String tenantId, String senderId,
      {String? verificationMethod}) async {
    if (failSubmit) throw StateError('upstream down');
    submittedSenderId = senderId;
    submittedMethod = verificationMethod;
    return WhatsAppSenderResponse.fromJson({
      'id': senderId,
      'tenantId': tenantId,
      'phoneE164': created!.phoneE164,
      'displayName': created!.displayName,
      'businessProfile': created!.businessProfile.toJson(),
      'status': 'PENDING_REVIEW',
      'onboarding': {'status': 'CREATING'},
      'defaultLocale': 'en-US',
    });
  }
}

Widget _wizard(_FakeAdminApi api, {WhatsAppSenderResponse? existing}) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: MaterialApp(
        theme: buildAppTheme(),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => WhatsAppSenderWizard(existing: existing))),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );

Future<void> _type(WidgetTester tester, String label, String value) async {
  final field = find.widgetWithText(TextField, label).hitTestable();
  await tester.ensureVisible(field);
  await tester.enterText(field, value);
  await tester.pump();
}

Future<void> _pick(WidgetTester tester, Finder dropdown, String option) async {
  await tester.ensureVisible(dropdown);
  // Tap the button, not the field — the field's bounds include its helper text.
  await tester.tap(find.descendant(
      of: dropdown, matching: find.byWidgetPredicate((w) => w is DropdownButton)));
  await tester.pumpAndSettle();
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

WhatsAppSenderResponse _draftWithLogo() => WhatsAppSenderResponse.fromJson({
      'id': 'snd_logo',
      'tenantId': 't1',
      'phoneE164': '+18095551234',
      'displayName': 'Test Co',
      'businessProfile': {
        'legalName': 'Test Inc.',
        'displayName': 'Test Co',
        'contactEmail': 'ops@test.example',
        'vertical': 'RESTAURANT',
        'logoUrl': '/v1/chat/logos/logo_1/logo.png',
      },
      'status': 'DRAFT',
      'onboarding': const <String, dynamic>{},
      'defaultLocale': 'en-US',
    });

/// The vertical Stepper keeps every step in the tree (inactive ones behind
/// IgnorePointer), so only a hit-testable match is the live one.
Future<void> _tapText(WidgetTester tester, String text) async {
  final target = find.text(text).hitTestable().first;
  await tester.ensureVisible(target);
  await tester.tap(target);
  await tester.pumpAndSettle();
}

Future<void> _fillBusinessProfile(WidgetTester tester, {String website = ''}) async {
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();

  await _type(tester, 'Legal business name *', 'Test Inc.');
  await _type(tester, 'Business display name *', 'Test Co');
  await _type(tester, 'Contact email *', 'ops@test.example');
  if (website.isNotEmpty) await _type(tester, 'Website URL', website);
  await _pick(tester, find.byType(DropdownButtonFormField<String>).first, 'Restaurant');
}

Future<void> _fillThroughReview(WidgetTester tester, {String website = ''}) async {
  await _fillBusinessProfile(tester, website: website);
  await _tapText(tester, 'Next');

  await _type(tester, 'Phone number (E.164) *', '+18095551234');
  await _tapText(tester, 'Voice call');
  await _tapText(tester, 'Next');

  await _type(tester, 'Public display name *', 'Test Co');
  await _pick(
      tester,
      find.ancestor(
          of: find.text('Assistant that answers this number'),
          matching: find.byType(DropdownButtonFormField<String>)),
      'Support Bot (support@3)');
  await _tapText(tester, 'Next');
}

/// Text the operator cannot fully read: cut to fewer lines than it needs,
/// or partly outside a clip — e.g. a floating label sliced by the Stepper.
/// Text a clip hides completely (inactive steps) is not a problem.
List<String> _unreadableText(WidgetTester tester) {
  final problems = <String>[];
  for (final p in tester.allRenderObjects.whereType<RenderParagraph>()) {
    if (!p.attached || !p.hasSize) continue;
    final text = p.text.toPlainText().trim();
    if (text.isEmpty) continue;
    final rect = MatrixUtils.transformRect(p.getTransformTo(null), Offset.zero & p.size);
    var clipped = false;
    for (RenderObject? node = p.parent; node != null; node = node.parent) {
      if (node is RenderClipRect && node.clipBehavior != Clip.none && node.hasSize) {
        final clip = MatrixUtils.transformRect(node.getTransformTo(null), Offset.zero & node.size);
        final visible = rect.intersect(clip);
        if (!visible.isEmpty && visible.height < rect.height - 0.5) clipped = true;
        if (visible.isEmpty) break;
      }
    }
    if (clipped) problems.add('clipped: "$text"');
    if (p.didExceedMaxLines) problems.add('cut off: "$text"');
  }
  return problems;
}

void main() {
  setUp(() {
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('save & submit creates the sender, routes it, and submits by voice',
      (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api));
    await tester.pumpAndSettle();
    await _fillThroughReview(tester);

    expect(find.text('Voice call').hitTestable(), findsOneWidget); // review row
    await _tapText(tester, 'Save & submit for review');

    expect(api.created?.phoneE164, '+18095551234');
    expect(api.created?.businessProfile.vertical, 'RESTAURANT');
    expect(api.binding?.endpoint.id, 'snd_new');
    expect(api.binding?.endpoint.kind, 'WHATSAPP_SENDER');
    expect(api.binding?.profileId, 'support');
    expect(api.binding?.profileVersion, 3);
    expect(api.binding?.routing.kind, 'MatchAny');
    expect(api.submittedSenderId, 'snd_new');
    expect(api.submittedMethod, 'voice');
    expect(find.textContaining('Submitted for WhatsApp review'), findsOneWidget);
  });

  testWidgets('a bare website domain is saved with https', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api));
    await tester.pumpAndSettle();
    await _fillThroughReview(tester, website: 'test.corillo.do');

    expect(find.text('https://test.corillo.do').hitTestable(), findsOneWidget); // review row
    await _tapText(tester, 'Save as draft');

    expect(api.created?.businessProfile.websiteUrl, 'https://test.corillo.do');
  });

  testWidgets('a website that is not a web address stops the business step', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api));
    await tester.pumpAndSettle();
    await _fillBusinessProfile(tester, website: 'not a website');
    await _tapText(tester, 'Next');

    expect(find.textContaining('Website must be a web address'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'Phone number (E.164) *').hitTestable(), findsNothing);
  });

  testWidgets('no step has clipped or cut-off text at laptop width', (tester) async {
    // Laptop width; tall so every control is on screen and tappable.
    tester.view.physicalSize = const Size(1280, 2400);
    addTearDown(tester.view.resetPhysicalSize);
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api));
    await tester.pumpAndSettle();

    // Typing floats each step's first label up onto the field border.
    await _fillBusinessProfile(tester, website: 'example.com');
    await tester.ensureVisible(find.widgetWithText(TextField, 'Legal business name *'));
    await tester.pumpAndSettle();
    expect(_unreadableText(tester), isEmpty, reason: 'business profile step');
    await _tapText(tester, 'Next');

    await _type(tester, 'Phone number (E.164) *', '+18095551234');
    await tester.pumpAndSettle();
    expect(_unreadableText(tester), isEmpty, reason: 'phone step');
    await _tapText(tester, 'Next');

    await _type(tester, 'Public display name *', 'Test Co');
    await tester.ensureVisible(find.widgetWithText(TextField, 'Public display name *'));
    await tester.pumpAndSettle();
    expect(_unreadableText(tester), isEmpty, reason: 'display name step');
    await _tapText(tester, 'Next');

    expect(_unreadableText(tester), isEmpty, reason: 'review step');
  });

  testWidgets('a saved profile photo is kept when the sender is edited', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api, existing: _draftWithLogo()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('Replace photo').hitTestable(), findsOneWidget);
    await _tapText(tester, 'Next');
    await _tapText(tester, 'Next');
    await _tapText(tester, 'Next');
    expect(find.text('Uploaded').hitTestable(), findsOneWidget); // review row
    await _tapText(tester, 'Save changes');

    expect(api.updated?.businessProfile.logoUrl, '/v1/chat/logos/logo_1/logo.png');
  });

  testWidgets('removing the profile photo saves the sender without one', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_wizard(api, existing: _draftWithLogo()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await _tapText(tester, 'Remove');
    expect(find.text('Upload photo').hitTestable(), findsOneWidget);
    await _tapText(tester, 'Next');
    await _tapText(tester, 'Next');
    await _tapText(tester, 'Next');
    await _tapText(tester, 'Save changes');

    expect(api.updated?.businessProfile.logoUrl, '');
  });

  testWidgets('a failed submit keeps the saved sender and says what is left to do',
      (tester) async {
    final api = _FakeAdminApi()..failSubmit = true;
    await tester.pumpWidget(_wizard(api));
    await tester.pumpAndSettle();
    await _fillThroughReview(tester);

    await _tapText(tester, 'Save & submit for review');

    expect(api.created, isNotNull);
    expect(api.binding, isNotNull);
    expect(find.textContaining('submit it from the list'), findsOneWidget);
  });
}

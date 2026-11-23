import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/template_list_kit.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/whatsapp_templates_screen.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_template.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

import 'template_test_support.dart';

Map<String, dynamic> _template(
  int id,
  String name,
  String status, {
  bool shared = false,
  String locale = 'es',
  String? rejectionReason,
}) => {
  'id': id,
  'tenantId': 't1',
  'senderId': 'snd_1',
  'name': name,
  'locale': locale,
  'category': 'UTILITY',
  'body': 'Aviso {{1}}.',
  'placeholderCount': 1,
  'status': status,
  'shared': shared,
  'rejectionReason': ?rejectionReason,
};

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.rows) : super(Dio());

  final List<Map<String, dynamic>> rows;
  final calls = <String>[];

  @override
  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async => [
    WhatsAppSenderResponse.fromJson({
      'id': 'snd_1',
      'tenantId': 't1',
      'phoneE164': '+18455336210',
      'displayName': 'Fast Cash',
      'businessProfile': {
        'legalName': 'Fast Cash SRL',
        'displayName': 'Fast Cash',
        'contactEmail': 'ops@fastcash.example',
        'vertical': 'RETAIL',
      },
      'status': 'ACTIVE',
      'defaultLocale': 'es',
    }),
  ];

  @override
  Future<List<WhatsAppTemplateResponse>> listWhatsAppTemplates(
    String tenantId,
    String senderId,
  ) async => rows.map(WhatsAppTemplateResponse.fromJson).toList();

  @override
  Future<List<WhatsAppTemplateResponse>> setWhatsAppTemplatesHidden(
    String tenantId,
    String senderId,
    List<int> templateIds, {
    required bool hidden,
  }) async {
    calls.add('${hidden ? 'hide' : 'unhide'}:$tenantId:$senderId:$templateIds');
    final changed = rows.where((r) => templateIds.contains(r['id'])).toList();
    for (final r in changed) {
      r['hidden'] = hidden;
    }
    return changed.map(WhatsAppTemplateResponse.fromJson).toList();
  }

  @override
  Future<WhatsAppTemplateResponse> createWhatsAppTemplate(
      String tenantId, String senderId, WhatsAppTemplateRequest body) async {
    calls.add('create:$tenantId:$senderId:${body.toJson()}');
    final row = _template(20, body.name, 'DRAFT', locale: body.locale)..['body'] = body.body;
    rows.add(row);
    return WhatsAppTemplateResponse.fromJson(row);
  }

  @override
  Future<WhatsAppTemplateResponse> updateWhatsAppTemplate(
      String tenantId, String senderId, int templateId, WhatsAppTemplateUpdate body) async {
    calls.add('update:$templateId:${body.toJson()}');
    final row = rows.firstWhere((r) => r['id'] == templateId)
      ..['body'] = body.body
      ..['category'] = body.category
      ..['status'] = 'DRAFT';
    return WhatsAppTemplateResponse.fromJson(row);
  }
}

class _SignedIn extends AuthController {
  _SignedIn({required this.platformAdmin});

  final bool platformAdmin;

  @override
  AuthState build() => AuthAuthenticated(
    Operator(
      subject: 'op-1',
      displayName: 'Op',
      source: 'user-password',
      platformAdmin: platformAdmin,
    ),
  );
}

Widget _screen(_FakeAdminApi api, {bool platformAdmin = false}) => ProviderScope(
  overrides: [
    adminApiProvider.overrideWith((ref) async => api),
    currentTenantOrNull.overrideWithValue('t1'),
    authProvider.overrideWith(() => _SignedIn(platformAdmin: platformAdmin)),
  ],
  child: const MaterialApp(home: Scaffold(body: WhatsAppTemplatesScreen())),
);

Future<List<String>> _menu(WidgetTester tester, String title) async {
  await tester.tap(find.byTooltip('More actions for $title'));
  await tester.pumpAndSettle();
  return tester
      .widgetList<PopupMenuItem<int>>(find.byType(PopupMenuItem<int>))
      .map((i) => ((i.child as ListTile).title as Text).data!)
      .toList();
}

Checkbox _checkbox(WidgetTester tester, int id) =>
    tester.widget<Checkbox>(find.descendant(of: find.byKey(ValueKey('select-$id')), matching: find.byType(Checkbox)));

Finder _field(String label) => find.descendant(
  of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
  matching: find.byType(TextField),
);

void main() {
  tearDown(useRealSheets);
  setUp(() {
    useTestSheets();
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 1600);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets(
    'a workspace admin hides its own older versions; a shared template is not theirs to hide',
    (tester) async {
      final api = _FakeAdminApi([
        _template(10, 'sl_automation_ask_v1', 'APPROVED', shared: true),
        _template(11, 'sl_automation_ask_v3', 'APPROVED', shared: true),
        _template(12, 'sl_ops_alert_v1', 'APPROVED'),
        _template(13, 'sl_ops_alert_v2', 'SUBMITTED'),
      ]);
      await tester.pumpWidget(_screen(api));
      await tester.pumpAndSettle();

      expect(find.text('Shared'), findsNWidgets(2));
      // Status is a word, never colour alone.
      expect(find.text('Approved'), findsNWidgets(3));
      expect(find.text('Pending review'), findsOneWidget);
      // Both older versions are tagged, but only the workspace's own is offered.
      expect(find.text('Older version'), findsNWidgets(2));
      expect(find.text('1 template has a newer version: sl_ops_alert_v1.'), findsOneWidget);
      expect(_checkbox(tester, 10).onChanged, isNull);
      expect(_checkbox(tester, 12).onChanged, isNotNull);
      expect(await _menu(tester, 'sl_automation_ask_v1 (es)'), ['Stop using']);
      await tester.tapAt(Offset.zero);
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'Hide older versions'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Hide 1'));
      await tester.pumpAndSettle();
      expect(api.calls, ['hide:t1:snd_1:[12]']);
      expect(find.text('sl_ops_alert_v1 (es)'), findsNothing);
      expect(find.text('Show hidden (1)'), findsOneWidget);

      // Hiding is undoable from the toast.
      await tester.tap(find.text('Undo'));
      await tester.pumpAndSettle();
      expect(api.calls.last, 'unhide:t1:snd_1:[12]');
      expect(find.text('sl_ops_alert_v1 (es)'), findsOneWidget);
    },
  );

  testWidgets('a platform admin may hide a shared template from the workspace view', (
    tester,
  ) async {
    final api = _FakeAdminApi([
      _template(10, 'sl_automation_ask_v1', 'APPROVED', shared: true),
      _template(11, 'sl_automation_ask_v3', 'APPROVED', shared: true),
    ]);
    await tester.pumpWidget(_screen(api, platformAdmin: true));
    await tester.pumpAndSettle();

    expect(find.text('1 template has a newer version: sl_automation_ask_v1.'), findsOneWidget);
    expect(await _menu(tester, 'sl_automation_ask_v1 (es)'), ['Hide', 'Stop using']);
    await tester.tap(find.text('Hide'));
    await tester.pumpAndSettle();
    expect(api.calls, ['hide:t1:snd_1:[10]']);
  });

  testWidgets('a rejected template shows WhatsApp’s reason in the list and in its sheet', (tester) async {
    final api = _FakeAdminApi([
      _template(30, 'promo_v1', 'REJECTED', rejectionReason: 'Variables at the start'),
    ]);
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    expect(find.text('Rejected'), findsOneWidget);
    expect(find.text('Variables at the start'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Submit for review'), findsOneWidget);

    await tester.tap(find.text('promo_v1 (es)'));
    await tester.pumpAndSettle();
    expect(find.text('WhatsApp’s reason: Variables at the start'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, 'Edit'), findsOneWidget);
  });

  testWidgets('the editor shows what is missing next to the field and does not save', (tester) async {
    final api = _FakeAdminApi([_template(12, 'sl_ops_alert_v1', 'APPROVED')]);
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'New template'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();

    expect(api.calls, isEmpty);
    expect(find.text('2 things need attention before creating this template'), findsOneWidget);
    expect(find.text('Enter a template name.'), findsWidgets);
    expect(find.text('Enter the message wording.'), findsWidgets);

    // A name and locale the sender already has is caught before the server.
    await tester.enterText(_field('Template name'), 'sl_ops_alert_v1');
    await tester.enterText(_field('Message'), '{{1}} is ready');
    await tester.pumpAndSettle();
    expect(find.textContaining('This sender already has sl_ops_alert_v1 in es'), findsWidgets);
    // Variable rules the review would reject are a warning, not a block.
    expect(find.textContaining('Start and end with words'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls, isEmpty);

    await tester.enterText(_field('Template name'), 'sl_ops_alert_v2');
    await tester.enterText(_field('Message'), 'Alerta: {{1}} está lista.');
    await tester.pumpAndSettle();
    expect(find.textContaining('Start and end with words'), findsNothing);
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls.single, startsWith('create:t1:snd_1:'));
    expect(api.calls.single, contains('name: sl_ops_alert_v2'));
    expect(api.calls.single, contains('locale: es'));
    expect(find.text('sl_ops_alert_v2 (es)'), findsOneWidget);
  });

  test('listing: hidden only on request, versions of a name together, chips per status', () {
    WhatsAppTemplateResponse t(int id, String name, {String locale = 'es', bool hidden = false, String status = 'APPROVED'}) =>
        WhatsAppTemplateResponse.fromJson({
          ..._template(id, name, status, locale: locale),
          'hidden': hidden,
        });
    final rows = [
      t(1, 'sl_a_v1'),
      t(2, 'sl_a_v3'),
      t(3, 'sl_a_v2'),
      t(4, 'sl_a_v1', locale: 'en'),
      t(5, 'sl_b', status: 'SUBMITTED'),
      t(6, 'sl_report_v1'),
      t(7, 'sl_c_v1', hidden: true),
    ];
    expect(newerVersions(rows), {1: 'sl_a_v3', 3: 'sl_a_v3'});
    expect(listedTemplates(rows, showHidden: false).map((r) => '${r.name}/${r.locale}'), [
      'sl_a_v3/es',
      'sl_a_v2/es',
      'sl_a_v1/en',
      'sl_a_v1/es',
      'sl_b/es',
      'sl_report_v1/es',
    ]);
    expect(listedTemplates(rows, showHidden: true).length, 7);
    expect(templateStatusFilters(rows).map((f) => f.label), ['Approved', 'Pending review']);
    expect(anyPendingReview(rows), isTrue);
  });

  test('variable rules mirror the backend’s declaration check', () {
    expect(placeholderCount('Hola {{1}}, cita {{3}}.'), 3);
    expect(placeholderIssues('Hola {{1}}, tu cita es {{2}}.'), isEmpty);
    expect(placeholderIssues('Hola {{2}}.').single.message, contains('{{1}} is missing'));
    expect(placeholderIssues('{{1}} llegó.').single.message, startsWith('Start and end with words'));
    expect(placeholderIssues('Hola {{1}}, {{2}} y más.').single.message, startsWith('Put words between'));
    final odd = placeholderIssues('Hola {{name}}.').single;
    expect(odd.declarationRule, isFalse);
  });
}

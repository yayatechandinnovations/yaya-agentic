import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/template_list_kit.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/whatsapp_templates_screen.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_template.dart';

Map<String, dynamic> _template(
  int id,
  String name,
  String status, {
  bool shared = false,
  String locale = 'es',
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

Finder _row(String title) =>
    find.ancestor(of: find.text(title), matching: find.byType(TemplateRowCard));

Future<List<String>> _menu(WidgetTester tester, String title) async {
  await tester.tap(find.descendant(of: _row(title), matching: find.byTooltip('More actions')));
  await tester.pumpAndSettle();
  return tester
      .widgetList<PopupMenuItem<int>>(find.byType(PopupMenuItem<int>))
      .map((i) => ((i.child as ListTile).title as Text).data!)
      .toList();
}

Checkbox _checkbox(WidgetTester tester, String title) =>
    tester.widget<Checkbox>(find.descendant(of: _row(title), matching: find.byType(Checkbox)));

void main() {
  setUp(() {
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
      // Both older versions are tagged, but only the workspace's own is offered.
      expect(find.text('Older version'), findsNWidgets(2));
      expect(find.text('1 template has a newer version: sl_ops_alert_v1.'), findsOneWidget);
      expect(_checkbox(tester, 'sl_automation_ask_v1 (es)').onChanged, isNull);
      expect(_checkbox(tester, 'sl_ops_alert_v1 (es)').onChanged, isNotNull);
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

  test('a later version of the same name and language replaces the earlier ones', () {
    WhatsAppTemplateResponse t(int id, String name, {String locale = 'es', bool hidden = false}) =>
        WhatsAppTemplateResponse.fromJson({
          ..._template(id, name, 'APPROVED', locale: locale),
          'hidden': hidden,
        });
    final rows = [
      t(1, 'sl_a_v1'),
      t(2, 'sl_a_v3'),
      t(3, 'sl_a_v2'),
      t(4, 'sl_a_v1', locale: 'en'),
      t(5, 'sl_b'),
      t(6, 'sl_report_v1'),
      t(7, 'sl_c_v1', hidden: true),
    ];
    expect(newerVersions(rows), {1: 'sl_a_v3', 3: 'sl_a_v3'});

    const filter = TemplateListFilter();
    expect(filter.apply(rows).map((r) => '${r.name}/${r.locale}'), [
      'sl_a_v3/es',
      'sl_a_v2/es',
      'sl_a_v1/en',
      'sl_a_v1/es',
      'sl_b/es',
      'sl_report_v1/es',
    ]);
    expect(filter.copyWith(showHidden: true).apply(rows).length, 7);
    expect(filter.copyWith(query: 'REPORT').apply(rows).single.id, 6);
    expect(filter.copyWith(status: () => 'DRAFT').apply(rows), isEmpty);
    expect(filter.statusCounts(rows), {'APPROVED': 6});
  });
}

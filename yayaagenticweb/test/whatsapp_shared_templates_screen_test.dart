import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/whatsapp_shared_templates_screen.dart';
import 'package:yayaagenticweb/models/admin/tenant.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_template.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

import 'template_test_support.dart';

Map<String, dynamic> _template(int id, String name, String status, List<String> holders) => {
      'id': id,
      'accountKey': 'platform',
      'name': name,
      'locale': 'es',
      'category': 'UTILITY',
      'body': 'Alerta {{1}}.',
      'placeholderCount': 1,
      'status': status,
      'headerKind': 'NONE',
      'buttons': <String>[],
      'holders': <Map<String, dynamic>>[
        for (final h in holders)
          {'tenantId': h, 'grantedBy': 'api_key:hub', 'grantedAt': '2026-11-16T12:00:00Z'},
      ],
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi([List<Map<String, dynamic>>? rows])
      : rows = rows ??
            [
              _template(1, 'sl_ops_alert_v1', 'APPROVED', ['fastcash']),
              _template(2, 'sl_draft_v1', 'DRAFT', []),
            ],
        super(Dio());

  final List<Map<String, dynamic>> rows;
  final calls = <String>[];

  @override
  Future<List<WhatsAppAccountTemplate>> setWhatsAppAccountTemplatesHidden(
      String accountKey, List<int> templateIds,
      {required bool hidden}) async {
    calls.add('${hidden ? 'hide' : 'unhide'}:$accountKey:$templateIds');
    final changed = rows.where((r) => templateIds.contains(r['id'])).toList();
    for (final r in changed) {
      r['hidden'] = hidden;
    }
    return changed.map(WhatsAppAccountTemplate.fromJson).toList();
  }

  @override
  Future<List<WhatsAppAccountResponse>> listWhatsAppAccounts() async => [
        WhatsAppAccountResponse(
            accountKey: 'platform', label: 'Platform account', status: 'ACTIVE', templateCount: 2),
      ];

  @override
  Future<List<WhatsAppAccountTemplate>> listWhatsAppAccountTemplates(String accountKey) async =>
      rows.map(WhatsAppAccountTemplate.fromJson).toList();

  @override
  Future<List<TenantResponse>> listTenants({String? status}) async => [
        const TenantResponse(id: 'fastcash', displayName: 'Fast Cash', status: 'ACTIVE'),
        const TenantResponse(id: 'test', displayName: 'Test', status: 'ACTIVE'),
      ];

  @override
  Future<List<WhatsAppAccountTemplate>> shareWhatsAppTemplates(
      String accountKey, List<int> templateIds, List<String> tenantIds) async {
    calls.add('share:$accountKey:$templateIds:$tenantIds');
    final changed = rows.where((r) => templateIds.contains(r['id'])).toList();
    for (final row in changed) {
      final holders = row['holders'] as List;
      for (final t in tenantIds) {
        if (holders.every((h) => h['tenantId'] != t)) {
          holders.add({'tenantId': t, 'grantedBy': 'me', 'grantedAt': null});
        }
      }
    }
    return changed.map(WhatsAppAccountTemplate.fromJson).toList();
  }

  @override
  Future<WhatsAppAccountTemplate> unshareWhatsAppTemplate(
      String accountKey, int templateId, String tenantId) async {
    calls.add('unshare:$accountKey:$templateId:$tenantId');
    final row = rows.firstWhere((r) => r['id'] == templateId);
    (row['holders'] as List).removeWhere((h) => h['tenantId'] == tenantId);
    return WhatsAppAccountTemplate.fromJson(row);
  }

  @override
  Future<WhatsAppAccountTemplate> declareWhatsAppAccountTemplate(
      String accountKey, WhatsAppTemplateDeclaration body) async {
    calls.add('declare:$accountKey:${body.toJson()}');
    final row = _template(3, body.name, 'DRAFT', []);
    rows.add(row);
    return WhatsAppAccountTemplate.fromJson(row);
  }
}

class _FailingShareApi extends _FakeAdminApi {
  @override
  Future<List<WhatsAppAccountTemplate>> shareWhatsAppTemplates(
          String accountKey, List<int> templateIds, List<String> tenantIds) async =>
      throw Exception('holder_not_allowed');
}

Future<void> _open(WidgetTester tester, String title) async {
  await tester.tap(find.text(title));
  await tester.pumpAndSettle();
}

Future<void> _close(WidgetTester tester) async {
  await tester.tap(find.byTooltip('Close (Esc)'));
  await tester.pumpAndSettle();
}

Future<List<String>> _menu(WidgetTester tester, String title) async {
  await tester.tap(find.byTooltip('More actions for $title'));
  await tester.pumpAndSettle();
  return tester
      .widgetList<PopupMenuItem<int>>(find.byType(PopupMenuItem<int>))
      .map((i) => ((i.child as ListTile).title as Text).data!)
      .toList();
}

Future<void> _select(WidgetTester tester, int id) async {
  await tester.tap(find.descendant(of: find.byKey(ValueKey('select-$id')), matching: find.byType(Checkbox)));
  await tester.pumpAndSettle();
}

CheckboxListTile _workspace(WidgetTester tester, String title) =>
    tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, title));

Finder _field(String label) => find.descendant(
      of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
      matching: find.byType(TextField),
    );

Widget _wrap(_FakeAdminApi api) => ProviderScope(
      overrides: [adminApiProvider.overrideWith((ref) async => api)],
      child: const MaterialApp(home: Scaffold(body: WhatsAppSharedTemplatesScreen())),
    );

void main() {
  setUp(() {
    useTestSheets();
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(useRealSheets);

  Future<void> bigView(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1600, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
  }

  testWidgets('lists each template once with its workspaces; share and stop sharing', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi();

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    expect(find.text('sl_ops_alert_v1 (es)'), findsOneWidget);
    expect(find.text('sl_draft_v1 (es)'), findsOneWidget);
    expect(find.text('Approved'), findsOneWidget);
    expect(find.text('Draft'), findsOneWidget);
    // Only what WhatsApp has not seen can be submitted or removed; the next
    // step shows on the row itself.
    expect(find.widgetWithText(FilledButton, 'Submit for review'), findsOneWidget);
    expect(await _menu(tester, 'sl_draft_v1 (es)'), ['Share…', 'Hide', 'Remove']);
    await tester.tapAt(Offset.zero);
    await tester.pumpAndSettle();
    expect(await _menu(tester, 'sl_ops_alert_v1 (es)'), ['Share…', 'Hide']);
    await tester.tapAt(Offset.zero);
    await tester.pumpAndSettle();

    await _open(tester, 'sl_draft_v1 (es)');
    expect(find.text('None yet — no workspace can send this template.'), findsOneWidget);
    await _close(tester);
    await _open(tester, 'sl_ops_alert_v1 (es)');
    expect(find.widgetWithText(InputChip, 'fastcash'), findsOneWidget);

    // Share the approved one: the workspace that already has it cannot be picked.
    await tester.tap(find.widgetWithText(ActionChip, 'Share with a workspace'));
    await tester.pumpAndSettle();
    expect(find.text('Share sl_ops_alert_v1'), findsOneWidget);
    expect(_workspace(tester, 'Fast Cash (fastcash)').onChanged, isNull);
    expect(find.text('Already uses it'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Share')).onPressed, isNull);
    await tester.tap(find.text('Test (test)'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Share with 1 workspace'));
    await tester.pumpAndSettle();
    expect(api.calls, ['share:platform:[1]:[test]']);
    // The open sheet follows the list.
    expect(find.widgetWithText(InputChip, 'test'), findsOneWidget);

    // Stop sharing asks first.
    await tester.tap(find.descendant(of: find.widgetWithText(InputChip, 'fastcash'), matching: find.byIcon(Icons.close)));
    await tester.pumpAndSettle();
    expect(find.text('Stop sharing with fastcash?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Stop sharing'));
    await tester.pumpAndSettle();
    expect(api.calls.last, 'unshare:platform:1:fastcash');
    expect(find.widgetWithText(InputChip, 'fastcash'), findsNothing);
  });

  testWidgets('a new template is checked like the backend checks a declaration', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi();

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'New template'));
    await tester.pumpAndSettle();

    await tester.enterText(_field('Template name'), 'Appt Reminder');
    await tester.enterText(_field('Message'), '{{1}}, tu cita es {{3}}.');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls, isEmpty);
    expect(find.textContaining('Use lowercase letters, digits and _ only'), findsWidgets);
    expect(find.textContaining('{{2}} is missing'), findsWidgets);
    expect(find.textContaining('Give an example for {{1}}, {{2}}, {{3}}'), findsWidgets);

    // An existing name on the account would replace its wording: caught here.
    await tester.enterText(_field('Template name'), 'sl_draft_v1');
    await tester.pumpAndSettle();
    expect(find.textContaining('sl_draft_v1 (es) is already on this account'), findsWidgets);

    await tester.enterText(_field('Template name'), 'appt_reminder_v1');
    await tester.enterText(_field('Message'), 'Hola {{1}}, tu cita es {{2}}.');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls, isEmpty);
    expect(find.textContaining('Give an example for {{1}}, {{2}}'), findsWidgets);

    await tester.enterText(find.byKey(const ValueKey('sample-1')), 'Ana');
    await tester.enterText(find.byKey(const ValueKey('sample-2')), 'lunes 3 p. m.');
    await tester.pumpAndSettle();
    // The preview reads with the examples in place.
    expect(find.text('Ana'), findsWidgets);
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls.single, contains('declare:platform:'));
    expect(api.calls.single, contains('body: [Ana, lunes 3 p. m.]'));
    expect(find.text('appt_reminder_v1 (es)'), findsOneWidget);
  });

  testWidgets('older versions are hidden in one go and come back with «Show hidden»', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([
      _template(1, 'sl_ops_alert_v1', 'APPROVED', ['fastcash']),
      _template(4, 'sl_ops_alert_v2', 'APPROVED', ['fastcash']),
      _template(2, 'sl_report_v1', 'APPROVED', ['fastcash']),
    ]);

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    // sl_report_v1 has no later version: it is current, whatever its suffix.
    expect(find.text('1 template has a newer version: sl_ops_alert_v1.'), findsOneWidget);
    expect(find.text('Older version'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Hide older versions'));
    await tester.pumpAndSettle();
    expect(find.text('Hide 1 older version?'), findsOneWidget);
    expect(find.text('sl_ops_alert_v1 (es)  →  sl_ops_alert_v2'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Hide 1'));
    await tester.pumpAndSettle();

    expect(api.calls, ['hide:platform:[1]']);
    expect(find.text('sl_ops_alert_v1 (es)'), findsNothing);
    expect(find.text('Hide older versions'), findsNothing);

    await tester.tap(find.text('Show hidden (1)'));
    await tester.pumpAndSettle();
    expect(find.text('Hidden'), findsOneWidget);

    expect(await _menu(tester, 'sl_ops_alert_v1 (es)'), ['Share…', 'Unhide']);
    await tester.tap(find.text('Unhide'));
    await tester.pumpAndSettle();
    expect(api.calls.last, 'unhide:platform:[1]');
    expect(find.text('Hidden'), findsNothing);
    expect(find.text('Hide older versions'), findsOneWidget);
  });

  testWidgets('search, status chips and hiding a selection', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([
      _template(1, 'sl_ops_alert_v1', 'APPROVED', ['fastcash']),
      _template(4, 'sl_ops_alert_v2', 'APPROVED', ['fastcash']),
      _template(2, 'sl_draft_v1', 'DRAFT', []),
    ]);

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    expect(find.text('All  3'), findsOneWidget);
    expect(find.text('Approved  2'), findsOneWidget);
    expect(find.text('Draft  1'), findsOneWidget);
    expect(find.textContaining('Rejected'), findsNothing);

    await tester.tap(find.text('Draft  1'));
    await tester.pumpAndSettle();
    expect(find.text('sl_draft_v1 (es)'), findsOneWidget);
    expect(find.text('sl_ops_alert_v2 (es)'), findsNothing);
    expect(find.text('1 of 3 templates'), findsOneWidget);
    await tester.tap(find.text('All  3'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'ops_alert');
    await tester.pumpAndSettle();
    expect(find.text('sl_draft_v1 (es)'), findsNothing);
    // Versions of one name sit together, the newest first.
    expect(tester.getTopLeft(find.text('sl_ops_alert_v2 (es)')).dy,
        lessThan(tester.getTopLeft(find.text('sl_ops_alert_v1 (es)')).dy));

    await _select(tester, 1);
    await _select(tester, 4);
    expect(find.text('2 selected'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Hide'));
    await tester.pumpAndSettle();
    expect(api.calls, ['hide:platform:[4, 1]']);
    expect(find.text('No templates match “ops_alert”.'), findsOneWidget);
  });

  testWidgets('a selection of templates is shared with several workspaces at once', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([
      _template(1, 'sl_ops_alert_v1', 'APPROVED', ['fastcash']),
      _template(4, 'sl_ops_alert_v2', 'APPROVED', ['fastcash']),
      _template(2, 'sl_draft_v1', 'DRAFT', []),
    ]);

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, 'Select all'));
    await tester.pumpAndSettle();
    expect(find.text('3 selected'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Share…'));
    await tester.pumpAndSettle();

    expect(find.text('Share 3 templates'), findsOneWidget);
    // A workspace that has some of them can still be given the rest.
    expect(find.text('Already uses 2 of 3'), findsOneWidget);
    expect(_workspace(tester, 'Fast Cash (fastcash)').onChanged, isNotNull);
    await tester.tap(find.text('Test (test)'));
    await tester.tap(find.text('Fast Cash (fastcash)'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Share with 2 workspaces'));
    await tester.pumpAndSettle();

    // Shown order: versions of a name together, the newest first.
    expect(api.calls, ['share:platform:[2, 4, 1]:[fastcash, test]']);
    expect(find.text('3 selected'), findsNothing);
    expect(find.text('Shared 3 templates with 2 workspaces'), findsOneWidget);
    await _open(tester, 'sl_draft_v1 (es)');
    expect(find.widgetWithText(InputChip, 'fastcash'), findsOneWidget);
    expect(find.widgetWithText(InputChip, 'test'), findsOneWidget);
  });

  testWidgets('a share that fails keeps the selection', (tester) async {
    await bigView(tester);
    final api = _FailingShareApi();

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, 'Select all'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Share…'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Test (test)'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Share with 1 workspace'));
    await tester.pumpAndSettle();
    expect(find.text('2 selected'), findsOneWidget);
  });

  test('a workspace template says when removing it only takes the workspace off', () {
    WhatsAppTemplateResponse row(String status, bool shared) => WhatsAppTemplateResponse.fromJson({
          'id': 1, 'name': 'x', 'status': status, 'shared': shared,
        });
    expect(row('DRAFT', false).staysInAccount, isFalse);
    expect(row('DRAFT', true).staysInAccount, isTrue);
    expect(row('APPROVED', false).staysInAccount, isTrue);
    expect(row('DRAFT', false).shared, isFalse);
  });
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/whatsapp_shared_templates_screen.dart';
import 'package:yayaagenticweb/models/admin/tenant.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_template.dart';

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
  _FakeAdminApi() : super(Dio());

  final rows = <Map<String, dynamic>>[
    _template(1, 'sl_ops_alert_v1', 'APPROVED', ['fastcash']),
    _template(2, 'sl_draft_v1', 'DRAFT', []),
  ];
  final calls = <String>[];

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
  Future<WhatsAppAccountTemplate> shareWhatsAppTemplate(
      String accountKey, int templateId, String tenantId) async {
    calls.add('share:$accountKey:$templateId:$tenantId');
    final row = rows.firstWhere((r) => r['id'] == templateId);
    (row['holders'] as List).add({'tenantId': tenantId, 'grantedBy': 'me', 'grantedAt': null});
    return WhatsAppAccountTemplate.fromJson(row);
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

Widget _wrap(_FakeAdminApi api) => ProviderScope(
      overrides: [adminApiProvider.overrideWith((ref) async => api)],
      child: const MaterialApp(home: Scaffold(body: WhatsAppSharedTemplatesScreen())),
    );

void main() {
  testWidgets('lists each template once with its workspaces; share and stop sharing', (tester) async {
    tester.view.physicalSize = const Size(1600, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final api = _FakeAdminApi();

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    expect(find.text('sl_ops_alert_v1 (es)'), findsOneWidget);
    expect(find.text('sl_draft_v1 (es)'), findsOneWidget);
    expect(find.widgetWithText(InputChip, 'fastcash'), findsOneWidget);
    expect(find.text('None yet — no workspace can send this template.'), findsOneWidget);
    // Only what WhatsApp has not seen can be submitted or removed.
    expect(find.text('Submit for review'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Remove'), findsOneWidget);

    // Share the approved one: the workspace that already has it is not offered.
    await tester.tap(find.widgetWithText(ActionChip, 'Share with a workspace').first);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).last);
    await tester.pumpAndSettle();
    expect(find.text('Fast Cash (fastcash)'), findsNothing);
    await tester.tap(find.text('Test (test)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Share'));
    await tester.pumpAndSettle();
    expect(api.calls, ['share:platform:1:test']);
    expect(find.widgetWithText(InputChip, 'test'), findsOneWidget);

    // Stop sharing asks first.
    await tester.tap(find.descendant(
        of: find.widgetWithText(InputChip, 'fastcash'), matching: find.byIcon(Icons.close)));
    await tester.pumpAndSettle();
    expect(find.text('Stop sharing with fastcash?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Stop sharing'));
    await tester.pumpAndSettle();
    expect(api.calls.last, 'unshare:platform:1:fastcash');
    expect(find.widgetWithText(InputChip, 'fastcash'), findsNothing);
  });

  testWidgets('adding a template needs one example per placeholder', (tester) async {
    tester.view.physicalSize = const Size(1600, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final api = _FakeAdminApi();

    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextField, 'Template name'), 'appt_reminder_v1');
    await tester.enterText(find.widgetWithText(TextField, 'Body'), 'Hola {{1}}, tu cita es {{2}}.');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();
    expect(api.calls, isEmpty);
    expect(find.textContaining('Give 2 example values'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'Example values'), 'Ana\nlunes 3 p. m.');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();
    expect(api.calls.single, contains('declare:platform:'));
    expect(api.calls.single, contains('body: [Ana, lunes 3 p. m.]'));
    expect(find.text('appt_reminder_v1 (es)'), findsOneWidget);
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

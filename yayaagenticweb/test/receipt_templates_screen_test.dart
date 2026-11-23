import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/receipt_templates/receipt_preview.dart';
import 'package:yayaagenticweb/features/admin/receipt_templates/receipt_templates_screen.dart';
import 'package:yayaagenticweb/models/admin/receipt_template.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

import 'template_test_support.dart';

Map<String, dynamic> _row(String id, String name) => {
      'id': id,
      'name': name,
      'kindLabel': 'Ticket',
      'paperCols': 32,
      'paperProfile': '58mm',
      'elements': [
        {'type': 'title', 'text': '{{docTitle}}'},
        {'type': 'divider', 'style': 'dashed'},
      ],
      'brand': {'accent': '#123456'},
      'createdAt': '2026-11-20T10:00:00Z',
      'updatedAt': '2026-11-21T10:00:00Z',
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi(this.rows) : super(Dio());

  final List<Map<String, dynamic>> rows;
  final calls = <String>[];
  final bodies = <Map<String, dynamic>>[];

  @override
  Future<List<ReceiptTemplateResponse>> listReceiptTemplates(String tenantId) async =>
      rows.map(ReceiptTemplateResponse.fromJson).toList();

  @override
  Future<ReceiptTemplateResponse> updateReceiptTemplate(String tenantId, String id, ReceiptTemplateRequest body) async {
    calls.add('update:$tenantId:$id');
    bodies.add(body.toJson());
    final i = rows.indexWhere((r) => r['id'] == id);
    rows[i] = {...rows[i], ...body.toJson()};
    return ReceiptTemplateResponse.fromJson(rows[i]);
  }

  @override
  Future<ReceiptTemplateResponse> createReceiptTemplate(String tenantId, ReceiptTemplateRequest body) async {
    calls.add('create:$tenantId');
    bodies.add(body.toJson());
    final row = {'id': 'rt_new', ...body.toJson()};
    rows.add(row);
    return ReceiptTemplateResponse.fromJson(row);
  }

  @override
  Future<void> deleteReceiptTemplate(String tenantId, String id) async {
    calls.add('delete:$tenantId:$id');
    rows.removeWhere((r) => r['id'] == id);
  }
}

Widget _wrap(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: ReceiptTemplatesScreen())),
    );

Finder _field(String label) => find.descendant(
      of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
      matching: find.byType(TextField),
    );

final _elements = find.byKey(const ValueKey('receipt-elements'));

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

  testWidgets('editing checks the layout like the backend and keeps the brand block', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([_row('rt_1', 'Quiniela ticket')]);
    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    expect(find.text('Quiniela ticket'), findsOneWidget);
    await tester.tap(find.text('Quiniela ticket'));
    await tester.pumpAndSettle();
    expect(find.byType(ReceiptPreview), findsOneWidget);
    expect(find.text('Valid layout · 2 elements'), findsOneWidget);

    await tester.enterText(_field('Name'), '');
    await tester.enterText(_field('Paper columns'), '90');
    await tester.enterText(
        _elements,
        jsonEncode([
          {'type': 'title', 'text': 'x'},
          {'type': 'banner'},
        ]));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Save template'));
    await tester.pumpAndSettle();

    expect(api.calls, isEmpty);
    expect(find.text('3 things need attention before saving'), findsOneWidget);
    expect(find.text('Enter a name.'), findsWidgets);
    expect(find.text('Use a number from 20 to 64.'), findsWidgets);
    expect(find.textContaining('Element 2 has type "banner"'), findsWidgets);
    // The preview keeps the last layout that parsed while the JSON is wrong.
    expect(find.byType(ReceiptPreview), findsOneWidget);

    await tester.enterText(
        _elements,
        jsonEncode([
          {
            'type': 'section',
            'elements': [
              {'type': 'section', 'elements': [{'type': 'text'}]},
            ],
          },
        ]));
    await tester.pumpAndSettle();
    expect(find.textContaining('sections can’t nest'), findsWidgets);

    await tester.enterText(_field('Name'), 'Quiniela ticket v2');
    await tester.enterText(_field('Paper columns'), '48');
    await tester.enterText(_elements, jsonEncode([{'type': 'title', 'text': '{{docTitle}}'}]));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Save template'));
    await tester.pumpAndSettle();

    expect(api.calls, ['update:t1:rt_1']);
    expect(api.bodies.single['name'], 'Quiniela ticket v2');
    expect(api.bodies.single['paperCols'], 48);
    expect(api.bodies.single['brand'], {'accent': '#123456'});
    expect(find.text('Quiniela ticket v2'), findsOneWidget);
  });

  testWidgets('a starter fills the layout; creating needs a name', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([]);
    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    expect(find.text('No receipt templates yet'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Create your first template'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sale receipt starter'));
    await tester.pumpAndSettle();
    expect(find.byType(ReceiptPreview), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls, isEmpty);
    expect(find.text('Enter a name.'), findsWidgets);

    await tester.enterText(_field('Name'), 'Sale receipt');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create template'));
    await tester.pumpAndSettle();
    expect(api.calls, ['create:t1']);
    expect((api.bodies.single['elements'] as List).first['type'], 'header');
    expect(find.text('Sale receipt'), findsOneWidget);
  });

  testWidgets('delete names the template and its consequence', (tester) async {
    await bigView(tester);
    final api = _FakeAdminApi([_row('rt_1', 'Quiniela ticket')]);
    await tester.pumpWidget(_wrap(api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quiniela ticket'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Delete'));
    await tester.pumpAndSettle();
    expect(find.text('Delete Quiniela ticket?'), findsOneWidget);
    expect(find.textContaining('fall back to unstyled rendering'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Delete template'));
    await tester.pumpAndSettle();
    expect(api.calls, ['delete:t1:rt_1']);
    expect(find.text('No receipt templates yet'), findsOneWidget);
  });

  test('the layout rules match the backend vocabulary', () {
    expect(receiptElementsProblem([{'type': 'qr'}]), isNull);
    expect(receiptElementsProblem({'type': 'qr'}), startsWith('The top level must be a JSON array'));
    expect(receiptElementsProblem([]), startsWith('Add at least one element'));
    expect(receiptElementsProblem(List.generate(81, (_) => {'type': 'spacer'})), startsWith('Use at most 80'));
    expect(receiptElementsProblem([{'type': 'section'}]), contains('without child "elements"'));
    expect(receiptElementsProblem(['x']), contains('must be an object'));
  });
}

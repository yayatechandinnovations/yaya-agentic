import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/features/chat/receipts/receipt_paper.dart';
import 'package:yayaagenticchat/models/receipts.dart';

/// CX-3 exit criterion (chat-experience-design.md §11): a template built from
/// the generic vocabulary reproduces the classic numbered-ticket anatomy —
/// header, meta, draw sections with play rows, boxed picks, total, emphasized
/// payment code, QR — from pure sample DATA. No domain word exists in the
/// template schema itself.
void main() {
  final classicTicket = ReceiptTemplate.fromJson({
    'id': 'rt_classic',
    'name': 'Numbered ticket',
    'paper': {'cols': 32, 'profile': '58mm'},
    'elements': [
      {'type': 'header', 'title': '{{org.name}}', 'subtitle': '{{station.name}}'},
      {'type': 'status', 'text': '{{statusStamp}}'},
      {'type': 'meta', 'left': '{{issuedAt}}', 'right': 'N° {{serial}}'},
      {'type': 'divider'},
      {
        'type': 'section',
        'repeat': 'sections',
        'title': '{{title}}',
        'subtitle': '{{subtitle}}',
        'elements': [
          {
            'type': 'table',
            'repeat': 'rows',
            'cols': [
              {'bind': 'numbers', 'align': 'left'},
              {'bind': 'game', 'align': 'center'},
              {'bind': 'amount', 'align': 'right', 'format': 'money'},
            ],
          },
        ],
      },
      {'type': 'cells', 'repeat': 'picks', 'bind': 'value'},
      {'type': 'total', 'label': '{{labels.total}}', 'value': '{{total | money}}'},
      {'type': 'code', 'label': '{{labels.paymentCode}}', 'value': '{{paymentCode | group:3}}'},
      {'type': 'kv', 'label': '{{labels.phone}}', 'value': '{{customer.phone | maskTail:4}}'},
      {'type': 'qr', 'value': '{{verifyUrl}}'},
      {'type': 'footer', 'lines': ['{{labels.checkTicket}}', '{{labels.thanks}}']},
    ],
  });

  final sampleData = <String, dynamic>{
    'org': {'name': 'BANCA EL SOL'},
    'station': {'name': 'Sucursal Centro'},
    'issuedAt': '2026-08-06 14:22',
    'serial': 'T-000123',
    'sections': [
      {
        'title': 'SORTEO NOCHE',
        'subtitle': 'Válido para: 06/08 21:00',
        'rows': [
          {'numbers': '23-45', 'game': 'PALE', 'amount': '50'},
          {'numbers': '07', 'game': 'QUINIELA', 'amount': '100'},
        ],
      },
    ],
    'picks': [
      {'value': '23'},
      {'value': '45'},
      {'value': '07'},
    ],
    'total': '150',
    'paymentCode': '482913',
    'customer': {'phone': '+18095551234'},
    'verifyUrl': 'https://x/verify/T-000123',
    'labels': {
      'total': 'Total',
      'paymentCode': 'Código de pago',
      'phone': 'WhatsApp',
      'checkTicket': 'Verifique su ticket.',
      'thanks': '¡Gracias!',
    },
  };

  testWidgets('classic ticket renders faithfully from sample data', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ReceiptPaper(template: classicTicket, data: sampleData),
        ),
      ),
    ));

    expect(find.text('BANCA EL SOL'), findsOneWidget);
    expect(find.text('SORTEO NOCHE'), findsOneWidget);
    expect(find.text('Válido para: 06/08 21:00'), findsOneWidget);
    expect(find.text('23-45'), findsOneWidget);
    expect(find.text('QUINIELA'), findsOneWidget);
    expect(find.textContaining('N° T-000123'), findsOneWidget);
    // Formatters: money, grouped payment code, masked phone tail.
    expect(find.text('150.00'), findsOneWidget);
    expect(find.text('482 913'), findsOneWidget);
    expect(find.text('*** *** 1234'), findsOneWidget);
    // Boxed picks grid.
    expect(find.text('23'), findsOneWidget);
    expect(find.text('45'), findsOneWidget);
    // Footer courtesy lines.
    expect(find.text('¡Gracias!'), findsOneWidget);
    // The unbound status stamp renders nothing (a sale, not a reprint).
    expect(find.textContaining('REPRINT'), findsNothing);
  });

  testWidgets('status stamp appears on a re-issued copy', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ReceiptPaper(
              template: classicTicket,
              data: {...sampleData, 'statusStamp': 'Reimpresión'}),
        ),
      ),
    ));
    expect(find.text('REIMPRESIÓN'), findsOneWidget);
  });

  test('binding is lookup-only — expressions never evaluate', () {
    expect(bindReceiptText('{{a}}', {'a': '1'}), '1');
    expect(bindReceiptText('{{a.b}}', {'a': {'b': 'x'}}), 'x');
    expect(bindReceiptText('{{missing}}', {}), '');
    // Unknown formatter degrades to the raw value; nothing is executed.
    expect(bindReceiptText('{{a | exec}}', {'a': 'rm -rf'}), 'rm -rf');
  });

  test('draft flag round-trips from the hint (default false)', () {
    final finalReceipt = ReceiptInstance.fromHint({
      'templateId': 'rt_1',
      'data': {'serial': 'T-1'},
    });
    expect(finalReceipt.draft, isFalse);

    final draft = ReceiptInstance.fromHint({
      'templateId': 'rt_1',
      'draft': true,
      'data': {'serial': 'DRAFT'},
    });
    expect(draft.draft, isTrue);
    expect(draft.data['serial'], 'DRAFT');
  });

  test('unknown element types are skipped, not fatal', () {
    final t = ReceiptTemplate.fromJson({
      'id': 'x',
      'name': 'X',
      'elements': [
        {'type': 'hologram', 'value': '{{a}}'},
        {'type': 'text', 'text': 'still here'},
      ],
    });
    expect(t.elements, hasLength(2)); // parsing keeps them; the renderer skips
  });
}

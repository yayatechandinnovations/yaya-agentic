import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/features/chat/receipts/receipt_paper.dart';
import 'package:yayaagenticchat/models/receipts.dart';

/// CX-3 exit criterion (chat-experience-design.md §11): the seeded "Numbered
/// ticket" template reproduces the real smartlotterysales printed ticket
/// (its `detailed` layout) field-for-field. The template mirrors
/// ReceiptTemplateDefaults.numberedTicket(); the sample data mirrors
/// smartlotterysales' own detailed_layout_render_test fixture (ticket
/// 100234-7788, SMART LOTTERY, a straight P3-STR and a P3-COMB expansion,
/// total 80), and the assertions are the strings the real ESC/POS encoder
/// prints. No domain word lives in the schema — only in data.
void main() {
  // Element-for-element replica of ReceiptTemplateDefaults.numberedTicket().
  final numberedTicket = ReceiptTemplate.fromJson({
    'id': 'rt_numbered',
    'name': 'Numbered ticket',
    'paper': {'cols': 32, 'profile': '58mm'},
    'elements': [
      {'type': 'header', 'title': '{{org.name}}', 'subtitle': '{{station.name}}'},
      {'type': 'status', 'text': '{{statusStamp}}'},
      {'type': 'kv', 'label': '{{labels.date}}', 'value': '{{issuedAt}}'},
      {'type': 'kv', 'label': '{{labels.ticket}}', 'value': '{{ticketRef}}'},
      {'type': 'divider'},
      {
        'type': 'section',
        'repeat': 'sections',
        'title': '{{title}}',
        'subtitle': '{{forDraws}}',
        'elements': [
          {
            'type': 'table',
            'repeat': 'rows',
            'cols': [
              {'bind': 'play', 'align': 'left'},
              {'bind': 'amount', 'align': 'right', 'format': 'money'},
            ],
          },
        ],
      },
      {'type': 'divider'},
      {
        'type': 'meta',
        'left': '{{labels.count}}: {{count}}',
        'right': '{{labels.total}}: {{total | money}}',
      },
      {'type': 'kv', 'label': '{{labels.cashDue}}', 'value': '{{payment.cashDue | money}}'},
      {'type': 'code', 'label': '{{labels.paymentCode}}', 'value': '{{paymentCode | group:2}}'},
      {'type': 'qr', 'value': '{{verifyUrl}}'},
      {'type': 'text', 'text': '{{ticketHash}}', 'align': 'center'},
      {'type': 'footer', 'lines': ['{{disclaimer}}', '{{labels.checkTicket}}', '{{labels.thanks}}']},
      {'type': 'text', 'text': '{{labels.printed}}: {{printedAt}}', 'align': 'center'},
    ],
  });

  // Mirrors smartlotterysales/test/.../detailed_layout_render_test.dart, with
  // the play descriptions the `detailed` layout composes (combo expansion).
  final ticketData = <String, dynamic>{
    'org': {'name': 'SMART LOTTERY'},
    'station': {'name': 'Sucursal Centro'},
    'issuedAt': '07/13/2026 02:24',
    'ticketRef': '100234-7788',
    'sections': [
      {
        'title': 'SMART LOTTERY',
        'forDraws': 'For draws: 10:00 AM',
        'rows': [
          {'play': 'P3-STR 123', 'amount': 10},
          {'play': 'P3-COMB 123 - 6 x \$10', 'amount': 60},
          {'play': 'P3-BOX 123', 'amount': 10},
        ],
      },
    ],
    'count': 3,
    'total': 80,
    'paymentCode': '482913',
    'verifyUrl': 'https://x/verify/100234-7788',
    'labels': {
      'date': 'Date',
      'ticket': 'Ticket',
      'count': 'Count',
      'total': 'Total',
      'cashDue': 'Cash to pay',
      'paymentCode': 'Payment code',
      'printed': 'Printed',
      'checkTicket': 'Please check your ticket.',
      'thanks': 'Thank you!',
    },
  };

  testWidgets('numbered ticket replicates the real smartlotterysales ticket',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ReceiptPaper(template: numberedTicket, data: ticketData),
        ),
      ),
    ));

    // Header + meta.
    expect(find.text('SMART LOTTERY'), findsWidgets); // org + section title
    expect(find.text('100234-7788'), findsOneWidget); // ticketNumber-reference
    expect(find.text('For draws: 10:00 AM'), findsOneWidget);
    // Play rows incl. the detailed combo expansion.
    expect(find.text('P3-COMB 123 - 6 x \$10'), findsOneWidget);
    // Count | Total line, money TRIMMED like the real formatter ($80, not $80.00).
    expect(find.text('Count: 3'), findsOneWidget);
    expect(find.text('Total: 80'), findsOneWidget);
    // Play amounts trimmed too.
    expect(find.text('60'), findsWidgets);
    // Payment code grouped by TWO (real: "48 29 13", not "482 913").
    expect(find.text('48 29 13'), findsOneWidget);
    // Unbound fields (status, cash-due, hash) print nothing on a plain sale.
    expect(find.textContaining('REPRINT'), findsNothing);
    expect(find.textContaining('Cash to pay'), findsNothing);
  });

  testWidgets('money formatter trims trailing zeros like formatTicketMoney',
      (tester) async {
    final tpl = ReceiptTemplate.fromJson({
      'id': 't', 'name': 't', 'paper': {'cols': 32, 'profile': '58mm'},
      'elements': [
        {'type': 'kv', 'label': 'a', 'value': '{{a | money}}'},
        {'type': 'kv', 'label': 'b', 'value': '{{b | money}}'},
        {'type': 'kv', 'label': 'c', 'value': '{{c | money}}'},
        {'type': 'kv', 'label': 'd', 'value': '{{d | money}}'},
      ],
    });
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
          body: ReceiptPaper(
              template: tpl,
              data: {'a': 1000, 'b': 1.5, 'c': 1.75, 'd': 1250.5})),
    ));
    expect(find.text('1,000'), findsOneWidget); // whole -> comma thousands, no decimals
    expect(find.text('1.5'), findsOneWidget); // x.x0 -> one decimal
    expect(find.text('1.75'), findsOneWidget); // else two
    expect(find.text('1,250.5'), findsOneWidget);
  });

  testWidgets('status stamp appears on a re-issued copy', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ReceiptPaper(
              template: numberedTicket,
              data: {...ticketData, 'statusStamp': 'Reimpresión'}),
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

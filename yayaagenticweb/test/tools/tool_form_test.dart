import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/admin/tools/tool_form.dart';
import 'package:yayaagenticweb/models/admin/tool.dart';

void main() {
  final source = ToolResponse(
    id: 'orders_refund',
    version: 3,
    tenant: 'acme',
    inputSchemaJson: '{"type":"object"}',
    outputSchemaJson: '{"type":"object"}',
    requires: const {'scope': 'orders:write'},
    handler: const ToolHandlerDto(
      kind: 'HTTP',
      httpSpec: HttpHandlerDto(
        method: 'POST',
        urlTemplate: '/v1/orders/{orderId}/refund',
        headerTemplates: {'X-Store': '{store.id}'},
        response: HttpResponseDto(
          jsonPath: r'$.data',
          headerOutputs: {'etag': 'ETag'},
          tableOutputs: {'rows': r'$.items'},
          cardFields: [{'label': 'Total', 'path': r'$.total'}],
        ),
        authForwarding: 'PRINCIPAL_TOKEN',
      ),
    ),
    policy: const {'confirmable': true, 'confirmPrompt': 'Refund?', 'timeout': 'PT5S'},
    captures: const [{'name': 'orderId'}],
    bindings: const [{'argName': 'orderId'}],
    suggestions: const [{'when': 'x'}],
  );

  test('a republish carries everything the editor does not show', () {
    final draft = ToolDraft.fromTool(source)..urlTemplate = '/v2/orders/{orderId}/refund';
    final carried = Map<String, dynamic>.from(source.policy)..removeWhere((k, _) => ToolDraft.policyKeys.contains(k));
    final req = draft.toRequest(tenant: 'acme', source: source, carriedPolicy: carried);

    expect(req.handler.httpSpec!.urlTemplate, '/v2/orders/{orderId}/refund');
    expect(req.requires, {'scope': 'orders:write'});
    expect(req.captures, source.captures);
    expect(req.bindings, source.bindings);
    expect(req.suggestions, source.suggestions);
    expect(req.policy['timeout'], 'PT5S');
    expect(req.policy['confirmPrompt'], 'Refund?');
    final resp = req.handler.httpSpec!.response!;
    expect(resp.headerOutputs, {'etag': 'ETag'});
    expect(resp.tableOutputs, {'rows': r'$.items'});
    expect(resp.cardFields, [{'label': 'Total', 'path': r'$.total'}]);
  });

  test('headless is only allowed on an HTTP GET that never waits for a person', () {
    final d = ToolDraft(method: 'GET');
    expect(d.headlessBlocker, isNull);
    d.confirmable = true;
    expect(d.headlessBlocker, contains('Ask before running'));
    d
      ..confirmable = false
      ..method = 'POST';
    expect(d.headlessBlocker, contains('HTTP GET'));
  });

  test('plannable needs confirmable and no secret input, and is dropped when blocked', () {
    final d = ToolDraft(plannable: true);
    expect(d.plannableBlocker, isNotNull);
    expect(d.toRequest(tenant: 't').policy['plannable'], isFalse);
    d.confirmable = true;
    expect(d.plannableBlocker, isNull);
    d.secretInput = true;
    expect(d.plannableBlocker, isNotNull);
  });

  test('snapshots round-trip through a draft', () {
    final a = ToolDraft.fromTool(source);
    final b = ToolDraft()..applySnapshot(a.toSnapshot());
    expect(b.toSnapshot(), a.toSnapshot());
  });

  test('summaries keep the latest version per id', () {
    final v1 = source.copyWith(version: 1);
    final other = source.copyWith(id: 'a_tool', version: 1);
    final s = summarizeTools([v1, source, other]);
    expect(s.map((x) => (x.latest.id, x.latest.version, x.versions.length)), [('a_tool', 1, 1), ('orders_refund', 3, 2)]);
  });
}

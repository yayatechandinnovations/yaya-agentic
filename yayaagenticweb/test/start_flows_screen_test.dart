import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/flows/flow_drafts.dart';
import 'package:yayaagenticweb/features/admin/flows/flows_admin_screen.dart';
import 'package:yayaagenticweb/models/admin/start_flow.dart';
import 'package:yayaagenticweb/models/admin/tool.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

/// A flow as the server returns it, with fields the builder has no control for.
Map<String, dynamic> _flowJson(int version, String status) => {
      'id': 'verify',
      'version': version,
      'tenantId': 't1',
      'name': 'Phone verification',
      'status': status,
      'steps': [
        {
          'kind': 'Elicit',
          'key': 'otp',
          'promptTemplate': 'Enter the code',
          'quickReplyTemplates': <String>[],
          'uiHintKind': 'otp_entry',
          'uiHintPayload': {'length': 6},
          'captureTarget': {'kind': 'StepVar', 'name': 'code'},
          'next': <dynamic>[],
        },
      ],
      'greeting': {
        'mode': 'HYBRID',
        'outcomeGreetingTemplates': {'success': 'Verified!', 'locked': 'Your account is locked.'},
        'fallbackGreeting': 'Hi!',
        'quickReplyTemplates': <String>[],
        'llmOpening': {
          'instruction': 'Be brief.',
          'briefingEntities': <String>[],
          'quickReplyHints': ['Help'],
          'stream': true,
          'budget': 'PT3S',
        },
      },
      'policy': {'totalBudget': 'PT2S', 'maxSteps': 5, 'runScope': 'EVERY_REOPEN', 'allowWhenAnonymous': true},
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final created = <StartFlowRequest>[];
  final updated = <StartFlowRequest>[];
  final published = <String>[];

  @override
  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async => [
        StartFlowResponse.fromJson(_flowJson(1, 'ACTIVE')),
        StartFlowResponse.fromJson(_flowJson(2, 'DRAFT')),
      ];

  @override
  Future<List<ToolResponse>> listTools({String tenant = 'default'}) async => const [];

  @override
  Future<StartFlowResponse> createStartFlow(String tenantId, StartFlowRequest body) async {
    created.add(body);
    return StartFlowResponse.fromJson({..._flowJson(1, 'DRAFT'), ...body.toJson()});
  }

  @override
  Future<StartFlowResponse> updateStartFlow(String tenantId, String id, StartFlowRequest body) async {
    updated.add(body);
    return StartFlowResponse.fromJson({..._flowJson(3, 'DRAFT'), ...body.toUpdateJson()});
  }

  @override
  Future<StartFlowResponse> publishStartFlow(String tenantId, String id) async {
    published.add(id);
    return StartFlowResponse.fromJson(_flowJson(3, 'ACTIVE'));
  }
}

Widget _app(_FakeAdminApi api, String location) {
  final router = GoRouter(
    initialLocation: location,
    routes: [GoRoute(path: '/admin/start-flows', builder: (_, _) => const Scaffold(body: StartFlowsAdminScreen()))],
  );
  return ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

Finder _field(String label) => find.descendant(
      of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
      matching: find.byType(TextField),
    );

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  test('a republish carries what the builder has no control for, and never sends a policy', () {
    final draft = FlowDraft.fromResponse(StartFlowResponse.fromJson(_flowJson(2, 'DRAFT')))..name = 'Renamed';
    final req = draft.toRequest(publish: false);
    final json = req.toUpdateJson();

    expect(json.containsKey('policy'), isFalse);
    expect(req.toJson().containsKey('policy'), isFalse);
    final step = (json['steps'] as List).single as Map;
    expect(step['uiHintPayload'], {'length': 6});
    final greeting = json['greeting'] as Map;
    expect(greeting['outcomeGreetingTemplates'], {'success': 'Verified!', 'locked': 'Your account is locked.'});
    expect(greeting['llmOpening'], {
      'quickReplyHints': ['Help'],
      'stream': true,
      'budget': 'PT3S',
      'instruction': 'Be brief.',
      'briefingEntities': <String>[],
    });

    // A draft snapshot restores to the same request.
    final restored = FlowDraft.fromSnapshot(draft.toSnapshot()).toRequest(publish: false).toUpdateJson();
    expect(restored, json);
  });

  test('validation mirrors the server: unique step IDs, known jump targets, a tool per step', () {
    final f = FlowDraft(id: 'bad id', name: '', steps: [
      RunToolDraft('a', next: [BranchDraft(targetKind: 'Step', stepKey: 'nowhere')]),
      ElicitDraft('a', promptTemplate: '', captureName: 'code'),
    ]);
    final r = validateFlow(f, editing: false);
    expect(r.errors['id'], isNotNull);
    expect(r.errors['name'], 'Enter a name.');
    expect(r.errors[FlowFields.step(0, 'tool')], 'Pick the tool this step runs.');
    expect(r.errors[FlowFields.branch(0, 0)], 'There is no step “nowhere”.');
    expect(r.errors[FlowFields.step(1, 'key')], contains('already uses “a”'));
    expect(r.errors[FlowFields.step(1, 'prompt')], isNotNull);
    expect(r.labels[FlowFields.step(1, 'prompt')], 'Step 2 (a) · Prompt');
  });

  testWidgets('a blank flow shows inline errors and is not created', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/start-flows?new=1'));
    await tester.pumpAndSettle();

    expect(find.text('New start flow'), findsWidgets);
    await _tap(tester, find.widgetWithText(OutlinedButton, 'Run a tool'));
    await _tap(tester, find.widgetWithText(FilledButton, 'Create draft'));

    expect(find.text('Enter a flow ID.'), findsWidgets);
    expect(find.text('Enter a name.'), findsWidgets);
    expect(find.text('Pick the tool this step runs.'), findsWidgets);
    expect(find.textContaining('need attention'), findsOneWidget);
    expect(api.created, isEmpty);
  });

  testWidgets('editing saves the next version as a draft, then publishes it when asked', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/start-flows?id=verify'));
    await tester.pumpAndSettle();

    expect(find.textContaining('v1 is live'), findsWidgets);
    await tester.enterText(_field('Name').first, 'Phone verification v3');
    await tester.pump();
    await _tap(tester, find.widgetWithText(OutlinedButton, 'Save and publish v3'));
    await _tap(tester, find.widgetWithText(FilledButton, 'Publish v3'));

    final sent = api.updated.single.toUpdateJson();
    expect(sent['name'], 'Phone verification v3');
    expect(sent.containsKey('policy'), isFalse);
    expect(((sent['steps'] as List).single as Map)['uiHintPayload'], {'length': 6});
    expect(api.published, ['verify']);
  });
}

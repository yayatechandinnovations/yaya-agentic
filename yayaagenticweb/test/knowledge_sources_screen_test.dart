import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/knowledge_sources/knowledge_sources_screen.dart';
import 'package:yayaagenticweb/models/admin/knowledge_source.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

Map<String, dynamic> _inline(int version, {String name = 'Return policy'}) => {
      'tenant': 't1',
      'id': 'returns',
      'version': version,
      'name': name,
      'locationKind': 'INLINE',
      'location': {
        'docs': [
          {'id': 'policy-md', 'contentType': 'text/markdown', 'text': '# Returns\n30 days.'},
          {'id': 'faq', 'contentType': 'text/plain', 'text': 'Second doc the editor does not show.'},
        ],
      },
      'ingestion': {'chunkSize': 400, 'chunkOverlap': 40},
      'retrieval': {'topK': 3},
      'access': {'scope': 'staff'},
      'status': 'READY',
      'docCount': 2,
      'chunkCount': 9,
      'lastIndexedAt': '2026-11-20T10:00:00Z',
    };

Map<String, dynamic> _upload(String id, String status, {String? lastError}) => {
      'tenant': 't1',
      'id': id,
      'version': 1,
      'name': 'Catalog $id',
      'locationKind': 'UPLOADED',
      'location': {
        'files': [
          {'blobId': 'b-$id', 'filename': '$id.pdf', 'contentType': 'application/pdf', 'sizeBytes': 2048, 'sha256': 'x'},
        ],
      },
      'status': status,
      'lastError': ?lastError,
    };

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final created = <CreateKnowledgeSourceRequest>[];
  final statusCalls = <String>[];

  @override
  Future<List<KnowledgeSourceResponse>> listKnowledgeSources({String tenant = 'default'}) async => [
        KnowledgeSourceResponse.fromJson(_inline(1, name: 'Old name')),
        KnowledgeSourceResponse.fromJson(_inline(2)),
        KnowledgeSourceResponse.fromJson(_upload('queued', 'UNINDEXED')),
        KnowledgeSourceResponse.fromJson(_upload('broken', 'ERROR', lastError: 'Tika could not parse page 3')),
      ];

  @override
  Future<Map<String, dynamic>> knowledgeSourceStatus({String tenant = 'default', required String id}) async {
    statusCalls.add(id);
    return id == 'queued'
        ? {'status': 'UNINDEXED', 'jobState': 'QUEUED', 'jobAttempts': 0, 'jobMaxAttempts': 5}
        : {'status': 'ERROR', 'jobState': 'FAILED', 'jobAttempts': 5, 'jobMaxAttempts': 5, 'jobLastError': 'boom'};
  }

  @override
  Future<KnowledgeSourceResponse> createKnowledgeSource(CreateKnowledgeSourceRequest req) async {
    created.add(req);
    return KnowledgeSourceResponse.fromJson({...req.toJson(), 'version': 3, 'status': 'UNINDEXED'});
  }
}

Widget _app(_FakeAdminApi api, String location) {
  final router = GoRouter(
    initialLocation: location,
    routes: [GoRoute(path: '/admin/knowledge-sources', builder: (_, _) => const Scaffold(body: KnowledgeSourcesScreen()))],
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

Future<void> _tap(WidgetTester tester, String label) async {
  final f = find.widgetWithText(FilledButton, label);
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2000);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  test('status reads the ingestion job: queued, retrying, failed with the reason', () {
    final queued = KnowledgeSourceResponse.fromJson(_upload('a', 'UNINDEXED'))
        .withJob(const KnowledgeIngestionJob(state: 'QUEUED', maxAttempts: 5));
    expect(knowledgeStatusOf(queued).label, 'Queued');
    expect(knowledgeStatusOf(queued).inFlight, isTrue);

    final retrying = KnowledgeSourceResponse.fromJson(_upload('a', 'ERROR', lastError: 'x'))
        .withJob(const KnowledgeIngestionJob(state: 'QUEUED', attempts: 2, maxAttempts: 5, lastError: 'timeout'));
    expect(knowledgeStatusOf(retrying).label, 'Retrying');
    expect(knowledgeStatusOf(retrying).detail, contains('Try 3 of 5'));

    final failed = KnowledgeSourceResponse.fromJson(_upload('a', 'ERROR', lastError: 'Tika could not parse'));
    expect(knowledgeStatusOf(failed).label, 'Failed');
    expect(knowledgeStatusOf(failed).detail, 'Tika could not parse');

    expect(knowledgeStatusOf(KnowledgeSourceResponse.fromJson(_inline(1))).label, 'Ready');
    final legacy = KnowledgeSourceResponse.fromJson({..._upload('a', 'UNINDEXED'), 'locationKind': 'LOCAL_PATH'});
    expect(knowledgeStatusOf(legacy).detail, contains('no longer supported'));
  });

  testWidgets('the list shows the latest version with job-aware status', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/knowledge-sources'));
    await tester.pumpAndSettle();

    expect(find.text('Return policy'), findsOneWidget);
    expect(find.text('Old name'), findsNothing);
    expect(find.text('Queued'), findsOneWidget);
    expect(find.text('Tika could not parse page 3'), findsOneWidget);
    // Only uploaded, not-ready sources ask for their job.
    expect(api.statusCalls.toSet(), {'queued', 'broken'});
  });

  testWidgets('a blank source shows inline errors and is not created', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/knowledge-sources?new=1'));
    await tester.pumpAndSettle();

    expect(find.text('New knowledge source'), findsWidgets);
    await _tap(tester, 'Create source');
    expect(find.text('Enter a source ID.'), findsWidgets);
    expect(find.text('Enter a name.'), findsWidgets);
    expect(find.text('Upload at least one file.'), findsWidgets);
    expect(find.textContaining('need attention'), findsOneWidget);

    await tester.enterText(_field('Source ID'), 'returns');
    await tester.enterText(_field('Name'), 'Returns again');
    await tester.tap(find.text('Paste text'));
    await tester.pump();
    await _tap(tester, 'Create source');
    expect(find.textContaining('already exists'), findsWidgets);
    expect(api.created, isEmpty);
  });

  testWidgets('publishing a new version keeps the settings and documents the form does not edit', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/knowledge-sources?id=returns'));
    await tester.pumpAndSettle();

    await tester.enterText(_field('Name'), 'Return policy 2027');
    await tester.enterText(_field('Text'), '# Returns\n60 days.');
    await _tap(tester, 'Publish v3');

    final sent = api.created.single.toJson();
    expect(sent['id'], 'returns');
    expect(sent['name'], 'Return policy 2027');
    expect(sent['ingestion'], {'chunkSize': 400, 'chunkOverlap': 40});
    expect(sent['retrieval'], {'topK': 3});
    expect(sent['access'], {'scope': 'staff'});
    final docs = (sent['location'] as Map)['docs'] as List;
    expect(docs, hasLength(2));
    expect(docs.first, {'id': 'policy-md', 'contentType': 'text/markdown', 'text': '# Returns\n60 days.'});
    expect((docs.last as Map)['id'], 'faq');
  });
}

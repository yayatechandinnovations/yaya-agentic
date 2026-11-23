import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/recording_strategies/recording_strategies_screen.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/recording_strategy.dart';
import 'package:yayaagenticweb/ui/form/y_json_field.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final created = <RecordingStrategyRequest>[];
  final lookups = <String>[];

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        for (final v in [1, 2])
          ProfileResponse(id: 'concierge', version: v, tenant: tenant, displayName: 'Concierge', introOneLiner: '', systemPromptFragment: ''),
        ProfileResponse(id: 'sales', version: 1, tenant: tenant, displayName: 'Sales desk', introOneLiner: '', systemPromptFragment: ''),
      ];

  @override
  Future<RecordingStrategyResponse?> getRecordingStrategy({
    String tenant = 'default',
    required String scopeKind,
    required String scopeId,
  }) async {
    lookups.add('$scopeKind/$scopeId');
    if (scopeKind == 'TENANT' && scopeId == 't1') {
      return RecordingStrategyResponse(
        tenant: 't1',
        scopeKind: 'TENANT',
        scopeId: 't1',
        version: 2,
        strategy: {
          'kind': 'fanout',
          'primary': 'postgres',
          'sinks': ['warehouse'],
          'redaction': {'pii': true},
        },
      );
    }
    if (scopeId == 'concierge') {
      return RecordingStrategyResponse(tenant: 't1', scopeKind: 'PROFILE', scopeId: 'concierge', version: 1, strategy: {'kind': 'single'});
    }
    return null;
  }

  @override
  Future<RecordingStrategyResponse> createRecordingStrategy(RecordingStrategyRequest req) async {
    created.add(req);
    return RecordingStrategyResponse(tenant: req.tenant, scopeKind: req.scopeKind, scopeId: req.scopeId, strategy: req.strategy, version: 3);
  }
}

Widget _app(_FakeAdminApi api, String location) {
  final router = GoRouter(
    initialLocation: location,
    routes: [GoRoute(path: '/admin/recording-strategies', builder: (_, _) => const Scaffold(body: RecordingStrategiesScreen()))],
  );
  return ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

Future<void> _tap(WidgetTester tester, String label) async {
  final f = find.widgetWithText(FilledButton, label);
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Finder get _settings => find.descendant(of: find.byType(YJsonField), matching: find.byType(TextField));

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2000);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('lists the strategy at every scope it can find', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/recording-strategies'));
    await tester.pumpAndSettle();

    expect(api.lookups.toSet(), {'TENANT/t1', 'PROFILE/concierge', 'PROFILE/sales'});
    expect(find.text('Every profile without its own'), findsOneWidget);
    expect(find.text('Concierge'), findsOneWidget);
    expect(find.text('Sales desk'), findsNothing);
    expect(find.text('Primary plus copies'), findsWidgets);
  });

  testWidgets('a profile strategy needs a profile and settings without “kind”', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/recording-strategies?new=1'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('One profile'));
    await tester.pumpAndSettle();
    await tester.enterText(_settings, '{"kind": "single"}');
    await tester.pump();
    await _tap(tester, 'Create strategy');

    expect(find.text('Pick the profile this strategy is for.'), findsWidgets);
    expect(find.textContaining('Remove “kind”'), findsWidgets);
    expect(find.textContaining('need attention'), findsOneWidget);
    expect(api.created, isEmpty);
  });

  testWidgets('publishing over the tenant strategy keeps its settings', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api, '/admin/recording-strategies?id=TENANT/t1'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Hot, then cold'));
    await tester.pumpAndSettle();
    // Switching kinds shows that kind's example; switching back restores the saved settings.
    await tester.tap(find.text('Primary plus copies').last);
    await tester.pumpAndSettle();
    await _tap(tester, 'Publish v3');

    final sent = api.created.single;
    expect(sent.scopeKind, 'TENANT');
    expect(sent.scopeId, 't1');
    expect(sent.strategy, {
      'kind': 'fanout',
      'primary': 'postgres',
      'sinks': ['warehouse'],
      'redaction': {'pii': true},
    });
  });
}

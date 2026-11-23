import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/api_config.dart';
import 'package:yayaagenticweb/app/live/admin_feed.dart';
import 'package:yayaagenticweb/app/live/event_source.dart';
import 'package:yayaagenticweb/app/live/live_resource.dart';
import 'package:yayaagenticweb/app/live/resource_kind.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';

class _FakeConnector implements FeedConnector {
  final urls = <Uri>[];
  late StreamController<FeedMessage> current;

  @override
  Stream<FeedMessage> connect(Uri url) {
    urls.add(url);
    current = StreamController<FeedMessage>();
    return current.stream;
  }
}

class _FakeSignals implements PageSignals {
  bool visible = true;
  bool online = true;
  final vis = StreamController<bool>.broadcast();
  final net = StreamController<bool>.broadcast();
  @override
  bool get isVisible => visible;
  @override
  bool get isOnline => online;
  @override
  Stream<bool> get visibilityChanges => vis.stream;
  @override
  Stream<bool> get onlineChanges => net.stream;
}

class _SignedIn extends AuthController {
  @override
  AuthState build() => const AuthAuthenticated(Operator(subject: 'op-1', displayName: 'Test Operator', source: 'local'));
}

void main() {
  late _FakeConnector connector;
  late _FakeSignals signals;
  late DateTime now;
  late ProviderContainer c;

  setUp(() {
    connector = _FakeConnector();
    signals = _FakeSignals();
    now = DateTime(2026, 11, 22, 10);
    c = ProviderContainer(overrides: [
      feedConnectorProvider.overrideWithValue(connector),
      pageSignalsProvider.overrideWithValue(signals),
      feedClockProvider.overrideWithValue(() => now),
      currentTenantOrNull.overrideWithValue('acme'),
      authProvider.overrideWith(_SignedIn.new),
      apiConfigProvider.overrideWith((ref) async => ApiConfig('https://console.example/api')),
    ]);
    addTearDown(c.dispose);
  });

  Future<void> settle() async {
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
  }

  Future<void> boot() async {
    await c.read(apiConfigProvider.future);
    c.listen(adminFeedProvider, (_, _) {});
    await settle();
  }

  test('connects to the tenant feed and goes live on hello', () async {
    await boot();
    expect(connector.urls.single.toString(), 'https://console.example/api/v1/admin/events?tenant=acme');
    connector.current.add(const FeedOpened());
    connector.current.add(const FeedEvent('hello', '{"feed":"abc"}'));
    await settle();
    expect(c.read(adminFeedProvider).status, SyncStatus.live);
  });

  test('a change bumps its kind and lists it as recent', () async {
    await boot();
    connector.current.add(const FeedEvent('resource-changed',
        '{"tenant":"acme","kind":"TOOL","id":"sales_checkout","version":4,"op":"UPDATED"}',
        lastEventId: 'b-7'));
    await settle();
    expect(c.read(adminChangeSignalProvider(AdminResourceKind.tool)), 1);
    expect(c.read(adminChangeSignalProvider(AdminResourceKind.profile)), 0);
    final recent = c.read(recentChangeProvider((kind: AdminResourceKind.tool, id: 'sales_checkout')));
    expect(recent?.version, 4);
    expect(recent?.op, AdminChangeOp.updated);
  });

  test('a reset from the server refreshes everything', () async {
    await boot();
    final before = c.read(refreshEpochProvider);
    connector.current.add(const FeedEvent('reset', '{}'));
    await settle();
    expect(c.read(refreshEpochProvider), before + 1);
  });

  test('a closed feed reconnects with its cursor so nothing is missed', () async {
    await boot();
    connector.current.add(const FeedEvent('resource-changed', '{"kind":"TOOL","id":"a","op":"CREATED"}', lastEventId: 'b-3'));
    connector.current.add(const FeedClosed());
    await settle();
    expect(c.read(adminFeedProvider).status, SyncStatus.reconnecting);
    await Future<void>.delayed(const Duration(milliseconds: 2100));
    expect(connector.urls, hasLength(2));
    expect(connector.urls.last.queryParameters, {'tenant': 'acme', 'lastEventId': 'b-3'});
  });

  test('coming back to the tab after a while refreshes lists', () async {
    await boot();
    final before = c.read(refreshEpochProvider);
    signals.vis.add(false);
    await settle();
    now = now.add(const Duration(minutes: 2));
    signals.vis.add(true);
    await settle();
    expect(c.read(refreshEpochProvider), before + 1);
  });

  test('a quick tab switch does not refetch', () async {
    await boot();
    final before = c.read(refreshEpochProvider);
    signals.vis.add(false);
    await settle();
    now = now.add(const Duration(seconds: 3));
    signals.vis.add(true);
    await settle();
    expect(c.read(refreshEpochProvider), before);
  });

  test('going offline is reported; coming back refreshes and reconnects', () async {
    await boot();
    signals.net.add(false);
    await settle();
    expect(c.read(adminFeedProvider).status, SyncStatus.offline);
    final before = c.read(refreshEpochProvider);
    signals.net.add(true);
    await settle();
    expect(c.read(refreshEpochProvider), before + 1);
    expect(connector.urls, hasLength(2));
  });

  test('a list provider using liveResource refetches when its kind changes', () async {
    var fetches = 0;
    final list = FutureProvider<int>((ref) async {
      liveResource(ref, AdminResourceKind.tool);
      return ++fetches;
    });
    await boot();
    c.listen(list, (_, _) {});
    expect(await c.read(list.future), 1);
    connector.current.add(const FeedEvent('resource-changed', '{"kind":"TOOL","id":"x","op":"CREATED"}'));
    await settle();
    expect(await c.read(list.future), 2);
    connector.current.add(const FeedEvent('resource-changed', '{"kind":"PROFILE","id":"p","op":"CREATED"}'));
    await settle();
    expect(await c.read(list.future), 2);
  });

  test('a feed whose tenant comes from a live list does not loop back into itself', () async {
    // Same shape as the app: the feed watches the tenant, the tenant is
    // derived from a list that refreshes on feed changes.
    final tenants = FutureProvider<List<String>>((ref) async {
      liveResource(ref, AdminResourceKind.tenant);
      return ['acme'];
    });
    final c2 = ProviderContainer(overrides: [
      feedConnectorProvider.overrideWithValue(connector),
      pageSignalsProvider.overrideWithValue(signals),
      feedClockProvider.overrideWithValue(() => now),
      currentTenantOrNull.overrideWith((ref) => ref.watch(tenants).valueOrNull?.first),
      authProvider.overrideWith(_SignedIn.new),
      apiConfigProvider.overrideWith((ref) async => ApiConfig('https://console.example/api')),
    ]);
    addTearDown(c2.dispose);
    await c2.read(apiConfigProvider.future);
    await c2.read(tenants.future);
    c2.listen(adminFeedProvider, (_, _) {});
    await settle();
    for (var i = 0; i < 3; i++) {
      connector.current.add(FeedEvent('resource-changed', '{"kind":"TENANT","op":"UPDATED","version":$i}'));
      await settle();
      c2.read(refreshEpochProvider.notifier).bump();
      await settle();
    }
    expect(c2.read(adminFeedProvider).status, isNot(SyncStatus.idle));
    expect(c2.read(adminChangeSignalProvider(AdminResourceKind.tenant)), 3);
  });
}

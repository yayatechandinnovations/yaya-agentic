import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/api_config.dart';
import '../../features/auth/application/auth_controller.dart';
import '../../features/auth/models/auth_state.dart';
import '../selected_tenant.dart';
import 'event_source.dart';
import 'resource_kind.dart';

enum SyncStatus {
  /// Nothing to follow yet (signed out, no tenant).
  idle,
  connecting,

  /// Change feed open: lists update within a second of a change.
  live,

  /// Feed dropped; retrying. Lists still refresh on focus and every 30 s.
  reconnecting,

  /// The browser reports no network. Data on screen is from [AdminFeedState.lastSyncAt].
  offline,
}

class AdminFeedState {
  const AdminFeedState({this.status = SyncStatus.idle, this.lastSyncAt});

  final SyncStatus status;
  final DateTime? lastSyncAt;

  AdminFeedState copyWith({SyncStatus? status, DateTime? lastSyncAt}) =>
      AdminFeedState(status: status ?? this.status, lastSyncAt: lastSyncAt ?? this.lastSyncAt);
}

// The signals and recent changes are leaf providers the feed *writes into*,
// never state derived from the feed: the feed watches the current tenant,
// the tenant comes from the (live) tenants list, and a list that depended
// back on the feed would make a cycle Riverpod only half detects.

/// Bumped per kind on every change; list providers listen to their kind.
class AdminChangeSignals extends Notifier<Map<AdminResourceKind, int>> {
  @override
  Map<AdminResourceKind, int> build() => const {};

  void bump(AdminResourceKind kind) => state = {...state, kind: (state[kind] ?? 0) + 1};
}

final adminChangeSignalsProvider =
    NotifierProvider<AdminChangeSignals, Map<AdminResourceKind, int>>(AdminChangeSignals.new);

/// Newest first, capped — drives row highlights and editor conflict banners.
class AdminRecentChanges extends Notifier<List<AdminChange>> {
  static const cap = 100;

  @override
  List<AdminChange> build() => const [];

  void add(AdminChange c) {
    final next = [c, ...state];
    state = next.length > cap ? next.sublist(0, cap) : next;
  }
}

final adminRecentChangesProvider =
    NotifierProvider<AdminRecentChanges, List<AdminChange>>(AdminRecentChanges.new);

final feedConnectorProvider = Provider<FeedConnector>((_) => defaultFeedConnector());
final pageSignalsProvider = Provider<PageSignals>((_) => defaultPageSignals());
final feedClockProvider = Provider<DateTime Function()>((_) => DateTime.now);

/// "Everything may be stale": tab refocused after a while, the feed lost its
/// cursor, the connection came back, or the 30 s fallback ticked while the
/// feed is down. Every live list refetches (lazily, if nobody is watching it).
class RefreshEpoch extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

final refreshEpochProvider = NotifierProvider<RefreshEpoch, int>(RefreshEpoch.new);

class AdminFeedController extends Notifier<AdminFeedState> {
  static const fallbackInterval = Duration(seconds: 30);
  static const refocusStaleAfter = Duration(seconds: 30);

  StreamSubscription<FeedMessage>? _feed;
  Timer? _retry;
  Timer? _fallback;
  int _attempt = 0;
  String? _lastEventId;
  DateTime? _hiddenSince;
  Uri? _url;

  DateTime _now() => ref.read(feedClockProvider)();

  @override
  AdminFeedState build() {
    final tenant = ref.watch(currentTenantOrNull);
    final auth = ref.watch(authProvider);
    final baseUrl = ref.watch(apiConfigProvider.select((c) => c.valueOrNull?.baseUrl));
    final signals = ref.read(pageSignalsProvider);

    ref.onDispose(_teardown);
    _lastEventId = null;
    _attempt = 0;

    final visSub = signals.visibilityChanges.listen(_onVisibility);
    final netSub = signals.onlineChanges.listen(_onOnline);
    ref.onDispose(() {
      visSub.cancel();
      netSub.cancel();
    });
    _fallback = Timer.periodic(fallbackInterval, (_) => _fallbackTick());

    if (tenant == null || auth is! AuthAuthenticated || baseUrl == null) {
      _url = null;
      return const AdminFeedState();
    }
    final base = Uri.parse(baseUrl);
    _url = base.replace(
      path: '${base.path.endsWith('/') ? base.path.substring(0, base.path.length - 1) : base.path}/v1/admin/events',
      queryParameters: {'tenant': tenant},
    );
    if (!signals.isOnline) return const AdminFeedState(status: SyncStatus.offline);
    scheduleMicrotask(_connect);
    return const AdminFeedState(status: SyncStatus.connecting);
  }

  /// Manual "Refresh now": refetch every list and, if the feed is down, retry it immediately.
  void refreshNow() {
    ref.read(refreshEpochProvider.notifier).bump();
    state = state.copyWith(lastSyncAt: _now());
    if (state.status != SyncStatus.live && _url != null) {
      _attempt = 0;
      _connect();
    }
  }

  void _teardown() {
    _feed?.cancel();
    _feed = null;
    _retry?.cancel();
    _fallback?.cancel();
  }

  void _connect() {
    final url = _url;
    if (url == null) return;
    _retry?.cancel();
    _feed?.cancel();
    final withCursor = _lastEventId == null
        ? url
        : url.replace(queryParameters: {...url.queryParameters, 'lastEventId': _lastEventId!});
    _feed = ref.read(feedConnectorProvider).connect(withCursor).listen(_onMessage);
  }

  void _onMessage(FeedMessage m) {
    switch (m) {
      case FeedOpened():
        final resumed = _attempt > 0 && _lastEventId == null;
        _attempt = 0;
        state = state.copyWith(status: SyncStatus.live, lastSyncAt: _now());
        // Without a cursor the server can't replay what happened while we
        // were away, so treat the reconnect as "maybe stale".
        if (resumed) ref.read(refreshEpochProvider.notifier).bump();
      case FeedEvent(:final type, :final data, :final lastEventId):
        if (lastEventId != null) _lastEventId = lastEventId;
        _onEvent(type, data);
      case FeedInterrupted():
        state = state.copyWith(status: SyncStatus.reconnecting);
      case FeedClosed():
        _feed?.cancel();
        _feed = null;
        _scheduleRetry();
    }
  }

  void _onEvent(String type, String data) {
    final now = _now();
    switch (type) {
      case 'hello':
        state = state.copyWith(status: SyncStatus.live, lastSyncAt: now);
      case 'reset':
        _lastEventId = null;
        ref.read(refreshEpochProvider.notifier).bump();
        state = state.copyWith(lastSyncAt: now);
      case 'resource-changed':
        Map<String, dynamic> json;
        try {
          json = jsonDecode(data) as Map<String, dynamic>;
        } catch (_) {
          return;
        }
        final change = AdminChange.fromJson(json, now);
        if (change == null) return;
        ref.read(adminRecentChangesProvider.notifier).add(change);
        ref.read(adminChangeSignalsProvider.notifier).bump(change.kind);
        state = state.copyWith(status: SyncStatus.live, lastSyncAt: now);
    }
  }

  void _scheduleRetry() {
    if (!ref.read(pageSignalsProvider).isOnline) {
      state = state.copyWith(status: SyncStatus.offline);
      return;
    }
    state = state.copyWith(status: SyncStatus.reconnecting);
    final seconds = min(30, 2 << min(_attempt, 4));
    _attempt++;
    _retry?.cancel();
    _retry = Timer(Duration(seconds: seconds), _connect);
  }

  void _fallbackTick() {
    if (_url == null || state.status == SyncStatus.live) return;
    if (!ref.read(pageSignalsProvider).isVisible || state.status == SyncStatus.offline) return;
    ref.read(refreshEpochProvider.notifier).bump();
    state = state.copyWith(lastSyncAt: _now());
  }

  void _onVisibility(bool visible) {
    if (!visible) {
      _hiddenSince = _now();
      return;
    }
    final since = _hiddenSince;
    _hiddenSince = null;
    if (since != null && _now().difference(since) >= refocusStaleAfter) {
      ref.read(refreshEpochProvider.notifier).bump();
      state = state.copyWith(lastSyncAt: _now());
    }
    if (_feed == null && _url != null) {
      _attempt = 0;
      _connect();
    }
  }

  void _onOnline(bool online) {
    if (!online) {
      state = state.copyWith(status: SyncStatus.offline);
      return;
    }
    ref.read(refreshEpochProvider.notifier).bump();
    _attempt = 0;
    _connect();
  }
}

final adminFeedProvider = NotifierProvider<AdminFeedController, AdminFeedState>(AdminFeedController.new);

final adminChangeSignalProvider = Provider.family<int, AdminResourceKind>(
  (ref, kind) => ref.watch(adminChangeSignalsProvider.select((s) => s[kind] ?? 0)),
);

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/directory_card.dart';
import '../models/entry_point_card.dart';
import '../models/tile.dart';
import 'chat_api.dart';
import 'directory_store.dart';

/// Overridden in main() once SharedPreferences has loaded.
final sharedPrefsProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('sharedPrefsProvider must be overridden in main()'),
);

final directoryStoreProvider = Provider<DirectoryStore>(
  (ref) => DirectoryStore(ref.watch(sharedPrefsProvider)),
);

/// Carries the verified-device pass (§20) so every chat call is identified as a
/// phone-verified human. Rebuilt (via invalidate) after verification completes.
final chatApiProvider = Provider<ChatApi>(
  (ref) => ChatApi(devicePass: ref.watch(directoryStoreProvider).devicePass()),
);

final deviceIdProvider = Provider<String>(
  (ref) => ref.watch(directoryStoreProvider).deviceId(),
);

/* ---- Phone verification (§20) ---- */

/// Phone-first flow: enter [phone] → the backend says whether the number is
/// registered. A returning phone skips straight to [code]; a new one stops at
/// [name] first so it can register (chat-channel-design.md §20).
enum VerifyStep { phone, name, code }

class VerificationState {
  final String? devicePass;
  final String? phone;
  final String? name;
  final VerifyStep step;
  final bool busy;
  final String? error;

  const VerificationState({
    this.devicePass,
    this.phone,
    this.name,
    this.step = VerifyStep.phone,
    this.busy = false,
    this.error,
  });

  bool get verified => devicePass != null && devicePass!.isNotEmpty;

  VerificationState copyWith({
    String? devicePass,
    String? phone,
    String? name,
    VerifyStep? step,
    bool? busy,
    String? error,
    bool clearError = false,
  }) =>
      VerificationState(
        devicePass: devicePass ?? this.devicePass,
        phone: phone ?? this.phone,
        name: name ?? this.name,
        step: step ?? this.step,
        busy: busy ?? this.busy,
        error: clearError ? null : (error ?? this.error),
      );
}

class VerificationNotifier extends StateNotifier<VerificationState> {
  VerificationNotifier(this._ref)
      : super(VerificationState(
          devicePass: _ref.read(directoryStoreProvider).devicePass(),
          phone: _ref.read(directoryStoreProvider).phone(),
          name: _ref.read(directoryStoreProvider).name(),
        ));

  final Ref _ref;
  // Verify endpoints don't need (and predate) a device pass.
  final ChatApi _api = ChatApi();

  /// Step 1 — the entered phone. A known number sends the OTP immediately and
  /// jumps to the code step; an unknown one stops to collect a name first.
  Future<void> submitPhone(String phone) async {
    state = state.copyWith(busy: true, clearError: true, phone: phone);
    try {
      final registered = await _api.verifyLookup(phone);
      if (registered) {
        await _api.verifyStart(phone, null);
        state = state.copyWith(busy: false, step: VerifyStep.code);
      } else {
        state = state.copyWith(busy: false, step: VerifyStep.name);
      }
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Step 2 (new users only) — capture the name, then send the OTP.
  Future<void> submitName(String name) async {
    if (state.phone == null) return;
    state = state.copyWith(busy: true, clearError: true, name: name);
    try {
      await _api.verifyStart(state.phone!, name);
      state = state.copyWith(busy: false, step: VerifyStep.code);
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Step 3 — verify the OTP and store the device pass. The backend echoes the
  /// account's name (the stored one for a returning phone), so keep that.
  Future<void> check(String code) async {
    if (state.phone == null) return;
    state = state.copyWith(busy: true, clearError: true);
    try {
      final deviceId = _ref.read(deviceIdProvider);
      final result = await _api.verifyCheck(state.phone!, code, deviceId, state.name);
      final name = result.name ?? state.name;
      await _ref.read(directoryStoreProvider).saveVerification(result.devicePass, state.phone!, name);
      _ref.invalidate(chatApiProvider); // pick up the new device pass
      state = state.copyWith(busy: false, devicePass: result.devicePass, name: name);
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Back to the phone step (from either the name or code step).
  void resetToPhone() => state = state.copyWith(step: VerifyStep.phone, clearError: true);
}

final verificationProvider =
    StateNotifierProvider<VerificationNotifier, VerificationState>(
  (ref) => VerificationNotifier(ref),
);

/// The user's directory of added tiles. Persisted on every mutation.
class TilesNotifier extends StateNotifier<List<Tile>> {
  TilesNotifier(this._store) : super(_store.loadTiles());

  final DirectoryStore _store;

  Future<void> addFromCard(EntryPointCard card) async {
    if (state.any((t) => t.handle == card.handle)) return;
    state = [...state, Tile.fromCard(card)];
    await _store.saveTiles(state);
  }

  Future<void> remove(String handle) async {
    state = state.where((t) => t.handle != handle).toList();
    await _store.saveTiles(state);
  }

  Future<void> setSession(String handle, String? sessionId) async {
    state = [
      for (final t in state)
        t.handle == handle ? t.copyWith(sessionId: sessionId, clearSession: sessionId == null) : t
    ];
    await _store.saveTiles(state);
  }

  Tile? byHandle(String handle) {
    for (final t in state) {
      if (t.handle == handle) return t;
    }
    return null;
  }
}

final tilesProvider = StateNotifierProvider<TilesNotifier, List<Tile>>(
  (ref) => TilesNotifier(ref.watch(directoryStoreProvider)),
);

/* ---- Public directory / Discover (chat-public-directory-design.md §8) ---- */

class DiscoverState {
  final String query;
  final List<DirectoryCard> items;
  final String? cursor; // the nextCursor to fetch; null when no more pages
  final bool loading; // first page / query change
  final bool loadingMore; // appending a keyset page
  final bool hasMore;
  final String? error;

  const DiscoverState({
    this.query = '',
    this.items = const [],
    this.cursor,
    this.loading = true,
    this.loadingMore = false,
    this.hasMore = false,
    this.error,
  });

  DiscoverState copyWith({
    String? query,
    List<DirectoryCard>? items,
    String? cursor,
    bool? loading,
    bool? loadingMore,
    bool? hasMore,
    String? error,
    bool clearError = false,
    bool clearCursor = false,
  }) =>
      DiscoverState(
        query: query ?? this.query,
        items: items ?? this.items,
        cursor: clearCursor ? null : (cursor ?? this.cursor),
        loading: loading ?? this.loading,
        loadingMore: loadingMore ?? this.loadingMore,
        hasMore: hasMore ?? this.hasMore,
        error: clearError ? null : (error ?? this.error),
      );
}

class DiscoverNotifier extends StateNotifier<DiscoverState> {
  DiscoverNotifier(this._api) : super(const DiscoverState()) {
    _load(reset: true);
  }

  final ChatApi _api;
  // Monotonic request id: a slow response for a stale query must never clobber a
  // newer one (search-as-you-type races).
  int _reqId = 0;

  Future<void> setQuery(String q) async {
    if (q == state.query) return;
    state = state.copyWith(query: q);
    await _load(reset: true);
  }

  Future<void> refresh() => _load(reset: true);

  Future<void> loadMore() async {
    if (state.loading || state.loadingMore || !state.hasMore) return;
    await _load(reset: false);
  }

  Future<void> _load({required bool reset}) async {
    final myReq = ++_reqId;
    state = reset
        ? state.copyWith(loading: true, clearError: true)
        : state.copyWith(loadingMore: true, clearError: true);
    try {
      final page = await _api.directory(q: state.query, cursor: reset ? null : state.cursor);
      if (myReq != _reqId) return; // superseded by a newer query
      state = state.copyWith(
        items: reset ? page.items : [...state.items, ...page.items],
        cursor: page.nextCursor,
        clearCursor: page.nextCursor == null,
        hasMore: page.nextCursor != null,
        loading: false,
        loadingMore: false,
      );
    } on ChatApiException catch (e) {
      if (myReq != _reqId) return;
      state = state.copyWith(loading: false, loadingMore: false, error: e.message);
    }
  }
}

/// autoDispose so leaving the Discover screen resets search + paging state.
final discoverProvider =
    StateNotifierProvider.autoDispose<DiscoverNotifier, DiscoverState>(
  (ref) => DiscoverNotifier(ref.watch(chatApiProvider)),
);

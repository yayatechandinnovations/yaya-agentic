import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/directory_card.dart';
import '../models/entry_point_card.dart';
import '../models/tile.dart';
import 'chat_api.dart';
import 'credential_store.dart';
import 'directory_store.dart';
import 'push_service.dart';

/// Overridden in main() once SharedPreferences has loaded.
final sharedPrefsProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('sharedPrefsProvider must be overridden in main()'),
);

/// Overridden in main() once secure storage has been read (and any legacy
/// plaintext pass migrated into it).
final initialCredentialsProvider = Provider<Credentials>(
  (ref) => throw UnimplementedError('initialCredentialsProvider must be overridden in main()'),
);

final credentialStoreProvider = Provider<CredentialStore>((ref) => CredentialStore());

final directoryStoreProvider = Provider<DirectoryStore>(
  (ref) => DirectoryStore(ref.watch(sharedPrefsProvider)),
);

/// Carries the verified-device pass (§20) so every chat call is identified as a
/// phone-verified human. Watches the verification state, so an unlock or pass
/// rotation rebuilds it with the fresh pass automatically.
final chatApiProvider = Provider<ChatApi>(
  (ref) => ChatApi(devicePass: ref.watch(verificationProvider.select((s) => s.devicePass))),
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
  final String? deviceSecret;
  final String? phone;
  final String? name;
  final VerifyStep step;
  final bool busy;
  final String? error;

  /// The pass is stale/absent but the device is enrolled — show the lock
  /// screen (or silently unlock when [pinRequired] is off).
  final bool locked;

  /// Does THIS device gate unlock behind the PIN?
  final bool pinRequired;

  /// Account-level: is a PIN set at all?
  final bool pinSet;

  /// One-shot nudge after first OTP on an account with no PIN.
  final bool pendingPinSetup;

  const VerificationState({
    this.devicePass,
    this.deviceSecret,
    this.phone,
    this.name,
    this.step = VerifyStep.phone,
    this.busy = false,
    this.error,
    this.locked = false,
    this.pinRequired = true,
    this.pinSet = false,
    this.pendingPinSetup = false,
  });

  bool get verified => devicePass != null && devicePass!.isNotEmpty;

  VerificationState copyWith({
    String? devicePass,
    String? deviceSecret,
    String? phone,
    String? name,
    VerifyStep? step,
    bool? busy,
    String? error,
    bool clearError = false,
    bool clearPass = false,
    bool? locked,
    bool? pinRequired,
    bool? pinSet,
    bool? pendingPinSetup,
  }) =>
      VerificationState(
        devicePass: clearPass ? null : (devicePass ?? this.devicePass),
        deviceSecret: deviceSecret ?? this.deviceSecret,
        phone: phone ?? this.phone,
        name: name ?? this.name,
        step: step ?? this.step,
        busy: busy ?? this.busy,
        error: clearError ? null : (error ?? this.error),
        locked: locked ?? this.locked,
        pinRequired: pinRequired ?? this.pinRequired,
        pinSet: pinSet ?? this.pinSet,
        pendingPinSetup: pendingPinSetup ?? this.pendingPinSetup,
      );
}

class VerificationNotifier extends StateNotifier<VerificationState> {
  VerificationNotifier(this._ref) : super(_initialState(_ref)) {
    _onStartup();
  }

  /// Startup decision (chat-experience-design.md §5.5): a valid pass proceeds;
  /// a stale pass with an enrolled device locks; anything else is onboarding.
  static VerificationState _initialState(Ref ref) {
    final store = ref.read(directoryStoreProvider);
    final creds = ref.read(initialCredentialsProvider);
    final passValid = !isJwtExpired(creds.devicePass);
    final enrolled = creds.deviceSecret != null && creds.deviceSecret!.isNotEmpty;
    return VerificationState(
      devicePass: passValid ? creds.devicePass : null,
      deviceSecret: creds.deviceSecret,
      phone: store.phone(),
      name: store.name(),
      locked: !passValid && enrolled,
      pinRequired: store.pinRequired(),
    );
  }

  final Ref _ref;
  // Verify endpoints don't need (and predate) a device pass.
  final ChatApi _api = ChatApi();

  CredentialStore get _creds => _ref.read(credentialStoreProvider);
  DirectoryStore get _store => _ref.read(directoryStoreProvider);

  Future<void> _onStartup() async {
    if (state.locked && !state.pinRequired) {
      // Trusted device ("don't ask on this phone"): unlock silently.
      await unlock(null);
    } else if (state.verified &&
        (state.deviceSecret == null || state.deviceSecret!.isEmpty)) {
      // Pre-CX install holding a long pass: trade it for a device secret once,
      // no SMS spent (lazy migration, §5.4).
      try {
        final e = await ChatApi(devicePass: state.devicePass).enrollDevice();
        await _creds.saveSecret(e.deviceSecret);
        await _store.savePinRequired(e.pinRequired);
        state = state.copyWith(
            deviceSecret: e.deviceSecret, pinRequired: e.pinRequired, pinSet: e.pinSet);
      } catch (_) {
        // Best-effort; the long pass keeps working until natural expiry.
      }
    }
  }

  /// PIN unlock (or silent unlock on a trusted device). Null [pin] is valid
  /// only when the device's PIN gate is off.
  Future<void> unlock(String? pin) async {
    final secret = state.deviceSecret;
    if (secret == null || secret.isEmpty) return;
    state = state.copyWith(busy: true, clearError: true);
    try {
      final deviceId = _ref.read(deviceIdProvider);
      final r = await _api.pinUnlock(deviceId, secret, pin);
      await _creds.savePass(r.devicePass);
      await _store.savePinRequired(r.pinRequired);
      if (r.phone != null) await _store.saveIdentity(r.phone!, r.name);
      state = state.copyWith(
        devicePass: r.devicePass,
        phone: r.phone ?? state.phone,
        name: r.name ?? state.name,
        locked: false,
        busy: false,
        pinRequired: r.pinRequired,
        pinSet: r.pinRequired ? true : state.pinSet,
      );
    } on ChatApiException catch (e) {
      if (e.code == 'otp_required') {
        // Ladder exhausted — only SMS re-verify recovers (§5.4).
        await reverifyBySms();
        return;
      }
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// "Forgot PIN? Verify by SMS": drop the stale pass, keep the identity and
  /// tiles, and land onboarding on the phone step (prefilled). A successful
  /// OTP re-enrolls the device and resets the lockout ladder server-side.
  Future<void> reverifyBySms() async {
    await _creds.clearPassOnly();
    state = VerificationState(
      deviceSecret: state.deviceSecret,
      phone: state.phone,
      name: state.name,
      pinRequired: state.pinRequired,
      locked: false,
    );
  }

  /// Called when a chat call 401s mid-session: the pass expired or was revoked
  /// remotely. Enrolled devices lock; others fall back to onboarding.
  Future<void> onAuthRejected() async {
    await _creds.clearPassOnly();
    final enrolled = state.deviceSecret != null && state.deviceSecret!.isNotEmpty;
    if (enrolled) {
      state = state.copyWith(clearPass: true, locked: true);
      if (!state.pinRequired) await unlock(null);
    } else {
      state = VerificationState(phone: state.phone, name: state.name);
    }
  }

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

  /// Step 3 — verify the OTP, store the pass + device secret, and (for an
  /// account without a PIN) queue the one-shot PIN-setup nudge. The backend
  /// echoes the account's name (the stored one for a returning phone).
  Future<void> check(String code) async {
    if (state.phone == null) return;
    state = state.copyWith(busy: true, clearError: true);
    try {
      final deviceId = _ref.read(deviceIdProvider);
      final result = await _api.verifyCheck(state.phone!, code, deviceId, state.name);
      final name = result.name ?? state.name;
      await _creds.savePass(result.devicePass);
      if (result.deviceSecret != null && result.deviceSecret!.isNotEmpty) {
        await _creds.saveSecret(result.deviceSecret!);
      }
      await _store.saveIdentity(state.phone!, name);
      await _store.savePinRequired(true);
      state = state.copyWith(
        busy: false,
        devicePass: result.devicePass,
        deviceSecret: result.deviceSecret ?? state.deviceSecret,
        name: name,
        locked: false,
        pinSet: result.pinSet,
        pendingPinSetup: !result.pinSet,
      );
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Adopt a freshly re-minted device pass after a rename (phone unchanged), a
  /// phone change, or a PIN set/remove (§6.1, §6.2, chat-experience §5.4).
  Future<void> applyRotatedPass(String devicePass, String phone, String name,
      {bool? pinSet}) async {
    await _creds.savePass(devicePass);
    await _store.saveIdentity(phone, name);
    state = state.copyWith(
        devicePass: devicePass, phone: phone, name: name, pinSet: pinSet ?? state.pinSet);
  }

  /// The PIN-setup nudge was completed or skipped.
  void dismissPinSetup({bool pinSet = false}) {
    state = state.copyWith(pendingPinSetup: false, pinSet: pinSet || state.pinSet);
  }

  /// Sign out locally after a delete or sign-out-everywhere (§6.3). The device
  /// pass is already revoked server-side; drop all local state so the app shell
  /// (which gates on [VerificationState.verified]) returns to onboarding.
  Future<void> signOut() async {
    await _unregisterPush();
    await _store.clearAll();
    await _creds.clear();
    state = const VerificationState();
  }

  /// Drop this device's push token while the pass is still valid — after the
  /// credentials clear, the unregister call would 401. Best-effort.
  Future<void> _unregisterPush() async {
    try {
      await _ref.read(pushServiceProvider).unregister();
    } catch (_) {
      // A signed-out device with a lingering token self-heals: the token is
      // pruned on the first failed send, or overwritten on next sign-in.
    }
  }

  /// Log out of THIS device only (chat-experience §5.4): the server row is
  /// already revoked; drop the credentials but keep the identity hints so the
  /// phone field comes prefilled next time. Tiles stay — they are local.
  Future<void> signOutThisDevice() async {
    await _unregisterPush();
    await _creds.clear();
    await _store.savePinRequired(true);
    state = VerificationState(phone: state.phone, name: state.name);
  }

  /// Back to the phone step (from either the name or code step).
  void resetToPhone() => state = state.copyWith(step: VerifyStep.phone, clearError: true);
}

final verificationProvider =
    StateNotifierProvider<VerificationNotifier, VerificationState>(
  (ref) => VerificationNotifier(ref),
);

/// Loads the signed-in account for the Account screen (§6). autoDispose so it
/// re-fetches on each open; it watches [chatApiProvider], so a rename/phone
/// change (which invalidates that provider) refreshes the shown name/phone.
final accountProvider = FutureProvider.autoDispose<Account>(
  (ref) => ref.watch(chatApiProvider).getAccount(),
);

/// The user's directory of added tiles. On-device is the fast path + offline
/// cache; for a verified user the server copy (§7.1) is unioned in on cold start
/// and mirrored on every add/remove (best-effort, never blocks the UI).
class TilesNotifier extends StateNotifier<List<Tile>> {
  TilesNotifier(this._ref) : super(_ref.read(directoryStoreProvider).loadTiles()) {
    unawaited(_reconcileWithServer());
    // Login often happens after this notifier already exists (onboarding / PIN
    // unlock in the same session), when the first reconcile bailed on an
    // unverified device. Re-pull the server tiles the moment the device becomes
    // verified so added chats appear without a page refresh.
    _ref.listen<VerificationState>(verificationProvider, (prev, next) {
      if (next.verified && (prev == null || !prev.verified)) {
        unawaited(_reconcileWithServer());
      }
    });
  }

  final Ref _ref;
  DirectoryStore get _store => _ref.read(directoryStoreProvider);
  ChatApi get _api => _ref.read(chatApiProvider);
  bool get _verified => _ref.read(verificationProvider).verified;

  /// Union the account's server-side tiles into the local set (§7.1, decision
  /// §14.4): server is the recovery source, local stays authoritative for
  /// offline adds — so we only ADD handles the device doesn't already have.
  Future<void> _reconcileWithServer() async {
    if (!_verified) return;
    try {
      final serverCards = await _api.accountTiles();
      final known = state.map((t) => t.handle).toSet();
      final additions = [
        for (final c in serverCards)
          if (!known.contains(c.handle)) Tile.fromCard(c),
      ];
      if (additions.isEmpty) return;
      state = [...state, ...additions];
      await _store.saveTiles(state);
    } catch (_) {
      // Recovery is best-effort; the local directory is never lost on failure.
    }
  }

  Future<void> addFromCard(EntryPointCard card) async {
    if (state.any((t) => t.handle == card.handle)) return;
    state = [...state, Tile.fromCard(card)];
    await _store.saveTiles(state);
    if (_verified) unawaited(_api.putAccountTile(card.handle));
  }

  Future<void> remove(String handle) async {
    state = state.where((t) => t.handle != handle).toList();
    await _store.saveTiles(state);
    if (_verified) unawaited(_api.deleteAccountTile(handle));
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
  (ref) => TilesNotifier(ref),
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

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

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

class VerificationState {
  final String? devicePass;
  final String? phone;
  final String? name;
  final bool codeSent;
  final bool busy;
  final String? error;

  const VerificationState({
    this.devicePass,
    this.phone,
    this.name,
    this.codeSent = false,
    this.busy = false,
    this.error,
  });

  bool get verified => devicePass != null && devicePass!.isNotEmpty;

  VerificationState copyWith({
    String? devicePass,
    String? phone,
    String? name,
    bool? codeSent,
    bool? busy,
    String? error,
    bool clearError = false,
  }) =>
      VerificationState(
        devicePass: devicePass ?? this.devicePass,
        phone: phone ?? this.phone,
        name: name ?? this.name,
        codeSent: codeSent ?? this.codeSent,
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

  Future<void> start(String phone, String name) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      await _api.verifyStart(phone, name);
      state = state.copyWith(busy: false, codeSent: true, phone: phone, name: name);
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  Future<void> check(String code) async {
    if (state.phone == null) return;
    state = state.copyWith(busy: true, clearError: true);
    try {
      final deviceId = _ref.read(deviceIdProvider);
      final pass = await _api.verifyCheck(state.phone!, code, deviceId, state.name);
      await _ref.read(directoryStoreProvider).saveVerification(pass, state.phone!, state.name);
      _ref.invalidate(chatApiProvider); // pick up the new device pass
      state = state.copyWith(busy: false, devicePass: pass);
    } on ChatApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  void resetCode() => state = state.copyWith(codeSent: false, clearError: true);
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

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/chat_api.dart';
import 'package:yayaagenticchat/data/credential_store.dart';
import 'package:yayaagenticchat/data/providers.dart';
import 'package:yayaagenticchat/models/entry_point_card.dart';

/// Server-side tiles the account has added (§7.1); returned by the fake once the
/// device is verified.
class _FakeApi extends ChatApi {
  _FakeApi(this.cards);
  final List<EntryPointCard> cards;
  @override
  Future<List<EntryPointCard>> accountTiles() async => cards;
}

class _FakeCreds extends CredentialStore {
  @override
  Future<Credentials> load(SharedPreferences prefs) async => const Credentials();
  @override
  Future<void> savePass(String value) async {}
  @override
  Future<void> saveSecret(String value) async {}
  @override
  Future<void> clear() async {}
  @override
  Future<void> clearPassOnly() async {}
}

EntryPointCard _card(String handle) => EntryPointCard(
      handle: handle,
      displayName: handle,
      logoUrl: '/l',
      authMode: 'ANONYMOUS_ONLY',
      defaultLocale: 'es',
    );

/// Guards the "added chats missing until page refresh" bug: the tiles list is
/// built while the device is still unverified (onboarding / PIN unlock), so the
/// first server reconcile bails — it must re-run when login completes.
void main() {
  test('tiles reload when the device becomes verified (no page refresh)', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final container = ProviderContainer(overrides: [
      sharedPrefsProvider.overrideWithValue(prefs),
      initialCredentialsProvider.overrideWithValue(const Credentials()),
      credentialStoreProvider.overrideWithValue(_FakeCreds()),
      chatApiProvider.overrideWithValue(_FakeApi([_card('e_abc')])),
    ]);
    addTearDown(container.dispose);

    // Home mounts the tiles list while still unverified: the first reconcile
    // bails, so nothing is pulled from the server yet.
    expect(container.read(tilesProvider), isEmpty);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(container.read(tilesProvider), isEmpty,
        reason: 'unverified → no server pull');

    // The user logs in during the same session.
    await container
        .read(verificationProvider.notifier)
        .applyRotatedPass('live-pass', '+18095551234', 'Ada');
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(container.read(tilesProvider).map((t) => t.handle), contains('e_abc'),
        reason: 'added chat appears after login without a refresh');
  });
}

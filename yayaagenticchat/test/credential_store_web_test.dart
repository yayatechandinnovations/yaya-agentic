@TestOn('browser')
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/credential_store.dart';

/// The "OTP every login" regression (chat-pin-vs-otp-login-fix.md): on web the
/// device secret must persist across launches, or the startup gate never sees an
/// enrolled device and always falls back to OTP. On web, prefs (localStorage) is
/// the durable source of truth — these run only in a browser (kIsWeb == true).
void main() {
  test('web: a saved secret is durably written to prefs', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = CredentialStore();
    await store.load(prefs);

    await store.saveSecret('enrolled-secret');
    await store.savePass('live-pass');

    expect(prefs.getString('yaya.chat.deviceSecret'), 'enrolled-secret');
    expect(prefs.getString('yaya.chat.devicePass'), 'live-pass');
  });

  test('web: a saved secret survives a fresh load on the next launch', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = CredentialStore();
    await store.load(prefs);
    await store.saveSecret('enrolled-secret');

    // A brand-new store, as on a cold start — the enrolled device must be seen.
    final reloaded = await CredentialStore().load(prefs);
    expect(reloaded.deviceSecret, 'enrolled-secret');
  });

  test('web: clear() removes the secret so sign-out is complete', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = CredentialStore();
    await store.load(prefs);
    await store.saveSecret('enrolled-secret');

    await store.clear();
    expect(prefs.getString('yaya.chat.deviceSecret'), isNull);
    expect((await CredentialStore().load(prefs)).deviceSecret, isNull);
  });
}

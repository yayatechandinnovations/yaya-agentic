import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/credential_store.dart';
import 'package:yayaagenticchat/data/providers.dart';

/// In-memory stand-in — the real one hits platform secure storage, which has
/// no implementation under `flutter test`.
class FakeCredentialStore extends CredentialStore {
  String? pass;
  String? secret;

  @override
  Future<Credentials> load(SharedPreferences prefs) async =>
      Credentials(devicePass: pass, deviceSecret: secret);

  @override
  Future<void> savePass(String value) async => pass = value;

  @override
  Future<void> saveSecret(String value) async => secret = value;

  @override
  Future<void> clear() async {
    pass = null;
    secret = null;
  }

  @override
  Future<void> clearPassOnly() async => pass = null;
}

/// Locks in the startup gate decision (chat-experience-design.md §5.5):
/// valid pass → app; stale pass + enrolled device → lock; neither → onboarding.
void main() {
  String fakeJwt({required DateTime exp}) {
    String b64(Map<String, dynamic> m) =>
        base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
    final header = b64({'alg': 'HS256', 'typ': 'JWT'});
    final payload = b64({'exp': exp.millisecondsSinceEpoch ~/ 1000, 'sub': 'chat:+1809'});
    return '$header.$payload.c2ln';
  }

  group('isJwtExpired', () {
    test('null / garbage / malformed are expired', () {
      expect(isJwtExpired(null), isTrue);
      expect(isJwtExpired(''), isTrue);
      expect(isJwtExpired('not-a-jwt'), isTrue);
      expect(isJwtExpired('a.b'), isTrue);
    });

    test('future exp is not expired', () {
      expect(isJwtExpired(fakeJwt(exp: DateTime.now().add(const Duration(hours: 2)))),
          isFalse);
    });

    test('past exp is expired', () {
      expect(isJwtExpired(fakeJwt(exp: DateTime.now().subtract(const Duration(hours: 2)))),
          isTrue);
    });

    test('exp within the skew window counts as expired', () {
      expect(isJwtExpired(fakeJwt(exp: DateTime.now().add(const Duration(seconds: 5)))),
          isTrue);
    });
  });

  group('VerificationNotifier startup gate', () {
    Future<ProviderContainer> makeContainer(Credentials creds,
        {Map<String, Object> prefsSeed = const {}}) async {
      SharedPreferences.setMockInitialValues(prefsSeed);
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        initialCredentialsProvider.overrideWithValue(creds),
        credentialStoreProvider.overrideWithValue(FakeCredentialStore()
          ..pass = creds.devicePass
          ..secret = creds.deviceSecret),
      ]);
      addTearDown(container.dispose);
      return container;
    }

    test('valid pass → verified, not locked', () async {
      final pass = fakeJwt(exp: DateTime.now().add(const Duration(days: 1)));
      final c = await makeContainer(Credentials(devicePass: pass, deviceSecret: 's'));
      final v = c.read(verificationProvider);
      expect(v.verified, isTrue);
      expect(v.locked, isFalse);
    });

    test('expired pass + enrolled device → locked', () async {
      final pass = fakeJwt(exp: DateTime.now().subtract(const Duration(days: 1)));
      final c = await makeContainer(Credentials(devicePass: pass, deviceSecret: 's'));
      final v = c.read(verificationProvider);
      expect(v.verified, isFalse);
      expect(v.locked, isTrue);
      expect(v.pinRequired, isTrue, reason: 'default until the server says otherwise');
    });

    test('expired pass, no secret → onboarding (not locked)', () async {
      final pass = fakeJwt(exp: DateTime.now().subtract(const Duration(days: 1)));
      final c = await makeContainer(Credentials(devicePass: pass),
          prefsSeed: {'yaya.chat.phone': '+18095551234'});
      final v = c.read(verificationProvider);
      expect(v.verified, isFalse);
      expect(v.locked, isFalse);
      expect(v.phone, '+18095551234', reason: 'phone survives for prefill');
    });

    test('nothing stored → onboarding', () async {
      final c = await makeContainer(Credentials.empty);
      final v = c.read(verificationProvider);
      expect(v.verified, isFalse);
      expect(v.locked, isFalse);
    });

    test('reverifyBySms keeps identity + secret, clears the lock', () async {
      final pass = fakeJwt(exp: DateTime.now().subtract(const Duration(days: 1)));
      final c = await makeContainer(Credentials(devicePass: pass, deviceSecret: 's'),
          prefsSeed: {'yaya.chat.phone': '+18095551234', 'yaya.chat.name': 'Ada'});
      await c.read(verificationProvider.notifier).reverifyBySms();
      final v = c.read(verificationProvider);
      expect(v.locked, isFalse);
      expect(v.verified, isFalse);
      expect(v.phone, '+18095551234');
      expect(v.deviceSecret, 's', reason: 'device stays enrolled through an SMS re-verify');
    });
  });
}

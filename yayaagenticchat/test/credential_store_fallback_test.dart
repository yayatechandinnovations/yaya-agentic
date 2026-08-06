import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_secure_storage_platform_interface/flutter_secure_storage_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/credential_store.dart';

/// A platform whose channel is missing — every call throws exactly like a web
/// build with a stale plugin registrant (the production incident this guards).
class _MissingChannelPlatform extends FlutterSecureStoragePlatform {
  @override
  Future<bool> containsKey({required String key, required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for containsKey');

  @override
  Future<void> delete({required String key, required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for delete');

  @override
  Future<void> deleteAll({required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for deleteAll');

  @override
  Future<String?> read({required String key, required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for read');

  @override
  Future<Map<String, String>> readAll({required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for readAll');

  @override
  Future<void> write(
          {required String key,
          required String value,
          required Map<String, String> options}) =>
      throw MissingPluginException('no implementation for write');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    FlutterSecureStoragePlatform.instance = _MissingChannelPlatform();
  });

  test('load degrades to prefs when the secure channel is missing', () async {
    SharedPreferences.setMockInitialValues({
      'yaya.chat.devicePass': 'legacy-pass',
      'yaya.chat.deviceSecret': 's3cret',
    });
    final prefs = await SharedPreferences.getInstance();
    final store = CredentialStore(const FlutterSecureStorage());

    final creds = await store.load(prefs);
    expect(creds.devicePass, 'legacy-pass');
    expect(creds.deviceSecret, 's3cret');
    // The legacy copy must survive — a failed migration never signs out.
    expect(prefs.getString('yaya.chat.devicePass'), 'legacy-pass');
  });

  test('saves and clears keep working through the prefs fallback', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = CredentialStore(const FlutterSecureStorage());
    await store.load(prefs);

    await store.savePass('new-pass');
    await store.saveSecret('new-secret');
    expect(prefs.getString('yaya.chat.devicePass'), 'new-pass');
    expect(prefs.getString('yaya.chat.deviceSecret'), 'new-secret');

    await store.clearPassOnly();
    expect(prefs.getString('yaya.chat.devicePass'), isNull);
    expect(prefs.getString('yaya.chat.deviceSecret'), 'new-secret');

    await store.clear();
    expect(prefs.getString('yaya.chat.deviceSecret'), isNull);
  });
}

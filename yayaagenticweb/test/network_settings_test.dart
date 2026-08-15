import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/models/admin/network_settings.dart';

void main() {
  group('NetworkSettings', () {
    test('fromJson reads all fields', () {
      final s = NetworkSettings.fromJson({
        'enabled': true,
        'playEnabled': true,
        'claimEnabled': false,
        'hubBaseUrl': 'https://hub.example',
        'hubCredentialRef': 'HUB_SECRET',
        'entryPointHandle': 'e_abc',
        'entryPointActive': true,
        'chatUrl': 'https://app/e/e_abc',
      });
      expect(s.enabled, true);
      expect(s.playEnabled, true);
      expect(s.claimEnabled, false);
      expect(s.hubBaseUrl, 'https://hub.example');
      expect(s.hubCredentialRef, 'HUB_SECRET');
      expect(s.entryPointHandle, 'e_abc');
      expect(s.entryPointActive, true);
      expect(s.chatUrl, 'https://app/e/e_abc');
    });

    test('fromJson tolerates a minimal payload', () {
      final s = NetworkSettings.fromJson({'enabled': false});
      expect(s.enabled, false);
      expect(s.playEnabled, false);
      expect(s.claimEnabled, false);
      expect(s.hubBaseUrl, isNull);
      expect(s.entryPointActive, false);
    });

    test('toJson sends only the writable fields', () {
      final json = NetworkSettings(
        enabled: true,
        playEnabled: false,
        claimEnabled: true,
        hubBaseUrl: 'https://hub.example',
        hubCredentialRef: 'HUB_SECRET',
        entryPointHandle: 'e_abc',
        entryPointActive: true,
        chatUrl: 'https://app/e/e_abc',
      ).toJson();
      expect(json, {
        'enabled': true,
        'playEnabled': false,
        'claimEnabled': true,
        'hubBaseUrl': 'https://hub.example',
        'hubCredentialRef': 'HUB_SECRET',
      });
      expect(json.containsKey('entryPointHandle'), false);
      expect(json.containsKey('chatUrl'), false);
    });

    test('toJson omits blank hub fields', () {
      final json = NetworkSettings(enabled: false, playEnabled: false, claimEnabled: false).toJson();
      expect(json.containsKey('hubBaseUrl'), false);
      expect(json.containsKey('hubCredentialRef'), false);
    });
  });
}

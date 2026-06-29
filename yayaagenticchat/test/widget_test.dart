import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticchat/config.dart';

void main() {
  group('AppConfig.handleFromDeepLink', () {
    test('extracts handle from https deep link', () {
      expect(AppConfig.handleFromDeepLink('https://chat.yaya.app/e/e_abc123'), 'e_abc123');
    });

    test('extracts handle from custom scheme', () {
      expect(AppConfig.handleFromDeepLink('yayachat://e/e_xyz'), 'e_xyz');
    });

    test('strips query and fragment', () {
      expect(AppConfig.handleFromDeepLink('https://chat.yaya.app/e/e_abc?ref=1#x'), 'e_abc');
    });

    test('accepts a bare handle', () {
      expect(AppConfig.handleFromDeepLink('e_bare'), 'e_bare');
    });

    test('returns null for unrelated urls', () {
      expect(AppConfig.handleFromDeepLink('https://example.com/foo'), isNull);
    });
  });
}

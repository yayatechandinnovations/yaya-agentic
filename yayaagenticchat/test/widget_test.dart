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

    test('recovers a link whose base was set without the /e/ segment', () {
      expect(
        AppConfig.handleFromDeepLink(
            'https://yayaagenticchat.smartlottery.do/e_yRbyNMh8InJiEF2qhBggu3AX'),
        'e_yRbyNMh8InJiEF2qhBggu3AX',
      );
    });

    test('recovers a mis-based link with a trailing slash and query', () {
      expect(
        AppConfig.handleFromDeepLink('https://host.example/e_abc/?x=1'),
        'e_abc',
      );
    });

    test('rejects a bare non-handle token', () {
      expect(AppConfig.handleFromDeepLink('hello'), isNull);
    });
  });
}

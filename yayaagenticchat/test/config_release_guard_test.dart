import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/config.dart';

void main() {
  group('AppConfig.isLoopback', () {
    test('flags localhost / loopback hosts', () {
      expect(AppConfig.isLoopback('http://localhost:8080'), isTrue);
      expect(AppConfig.isLoopback('http://127.0.0.1:8080'), isTrue);
      expect(AppConfig.isLoopback('http://0.0.0.0:8080'), isTrue);
      expect(AppConfig.isLoopback('http://[::1]:8080'), isTrue);
      expect(AppConfig.isLoopback('http://mymac.local'), isTrue);
    });

    test('accepts real production hosts', () {
      expect(AppConfig.isLoopback('https://yayaagentic.smartlottery.do'), isFalse);
      expect(AppConfig.isLoopback('https://chat.yaya.app'), isFalse);
    });

    test('treats an unparseable base as non-loopback (guard errs toward booting)', () {
      // A blank/garbage base has no loopback host, so the guard lets the app run
      // and surfaces the failure at request time rather than blocking startup.
      expect(AppConfig.isLoopback(''), isFalse);
    });
  });
}

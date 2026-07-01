import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/models/directory_card.dart';

void main() {
  group('DirectoryPage.fromJson', () {
    test('parses items and nextCursor', () {
      final page = DirectoryPage.fromJson({
        'items': [
          {
            'handle': 'e_abc',
            'displayName': 'Buy Yaya Market',
            'logoUrl': '/v1/chat/logos/1',
            'accentColorHex': '#0A7',
            'tagline': 'Groceries, delivered',
            'authMode': 'ANONYMOUS_ONLY',
          }
        ],
        'nextCursor': 'b64cursor',
      });
      expect(page.items, hasLength(1));
      expect(page.items.first.handle, 'e_abc');
      expect(page.items.first.tagline, 'Groceries, delivered');
      expect(page.nextCursor, 'b64cursor');
    });

    test('last page has null nextCursor and tolerates missing fields', () {
      final page = DirectoryPage.fromJson({
        'items': [
          {'handle': 'e_x', 'displayName': 'X', 'logoUrl': '/l'}
        ],
      });
      expect(page.nextCursor, isNull);
      expect(page.items.first.authMode, 'ANONYMOUS_ONLY'); // default
      expect(page.items.first.tagline, isNull);
    });

    test('empty page', () {
      final page = DirectoryPage.fromJson({'items': const []});
      expect(page.items, isEmpty);
      expect(page.nextCursor, isNull);
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/models/chat_message.dart';

void main() {
  group('InboxMessage / ChatAttachment (system-messaging §11, §10)', () {
    test('parses a drained system message with a document attachment', () {
      final m = InboxMessage.fromJson({
        'sessionId': 'sess-1',
        'senderDisplay': 'Cash Register #3',
        'subject': 'cash closure',
        'body': 'cash closed: 1000',
        'attachments': [
          {
            'assetId': 'media_1',
            'contentType': 'application/pdf',
            'filename': 'closure.pdf',
            'sizeBytes': 2048,
            'url': '/v1/media/tok',
          }
        ],
      });
      expect(m.sessionId, 'sess-1');
      expect(m.senderDisplay, 'Cash Register #3');
      expect(m.body, 'cash closed: 1000');
      expect(m.attachments, hasLength(1));
      expect(m.attachments.first.filename, 'closure.pdf');
      expect(m.attachments.first.isImage, isFalse);
      expect(m.attachments.first.url, '/v1/media/tok');
    });

    test('image attachments classify correctly', () {
      final a = ChatAttachment.fromJson(
          {'assetId': 'm', 'contentType': 'image/png', 'url': '/v1/media/x'});
      expect(a.isImage, isTrue);
    });

    test('tolerates a media-only / empty attachment list', () {
      final m = InboxMessage.fromJson({'sessionId': 's', 'body': ''});
      expect(m.attachments, isEmpty);
      expect(m.senderDisplay, isNull);
    });
  });
}

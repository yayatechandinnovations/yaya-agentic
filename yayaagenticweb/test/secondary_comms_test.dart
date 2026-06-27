import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/models/admin/secondary_comms.dart';

void main() {
  group('SecondaryComms', () {
    test('empty payload decodes to empty config', () {
      final s = SecondaryComms.fromJson({});
      expect(s.hasAny, isFalse);
      expect(s.emailRelay, isNull);
      expect(s.smsBackup, isNull);
    });

    test('round-trip preserves email relay + sms backup', () {
      final original = SecondaryComms(
        emailRelay:
            EmailRelay(fromAddress: 'noreply@acme.example', providerId: 'platform-smtp'),
        smsBackup: SmsBackup(fromNumber: '+15550100123', providerId: 'twilio-sms'),
      );
      final decoded = SecondaryComms.fromJson(original.toJson());
      expect(decoded.emailRelay?.fromAddress, equals('noreply@acme.example'));
      expect(decoded.emailRelay?.providerId, equals('platform-smtp'));
      expect(decoded.smsBackup?.fromNumber, equals('+15550100123'));
      expect(decoded.smsBackup?.providerId, equals('twilio-sms'));
    });

    test('decodes legacy payload missing providerId fields', () {
      final s = SecondaryComms.fromJson({
        'emailRelay': {'fromAddress': 'noreply@acme.example'},
        'smsBackup': {'fromNumber': '+15550100123'},
      });
      expect(s.emailRelay?.providerId, isNull);
      expect(s.smsBackup?.providerId, isNull);
      // Round-trip drops the now-null providerId rather than re-emitting null.
      final encoded = s.toJson();
      expect(encoded['emailRelay'], equals({'fromAddress': 'noreply@acme.example'}));
      expect(encoded['smsBackup'], equals({'fromNumber': '+15550100123'}));
    });

    test('hasAny is true when either side is configured', () {
      expect(
          SecondaryComms(emailRelay: EmailRelay(fromAddress: 'a@b.example'))
              .hasAny,
          isTrue);
      expect(
          SecondaryComms(smsBackup: SmsBackup(fromNumber: '+15550100123'))
              .hasAny,
          isTrue);
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';

WhatsAppSenderResponse _sender(String status, String? rawStatus) =>
    WhatsAppSenderResponse.fromJson({
      'id': 'snd_1',
      'tenantId': 't1',
      'phoneE164': '+18095551234',
      'displayName': 'Test Co',
      'businessProfile': {
        'legalName': 'Test Inc.',
        'displayName': 'Test Co',
        'contactEmail': 'ops@test.example',
        'vertical': 'RETAIL',
      },
      'status': status,
      'onboarding': {'status': rawStatus},
      'defaultLocale': 'es-DO',
    });

void main() {
  test('legacy vertical codes normalize to a dropdown entry', () {
    expect(normalizeBusinessVertical('RETAIL'), 'SHOPPING_AND_RETAIL');
    expect(normalizeBusinessVertical('HEALTH_AND_WELLNESS'), 'MEDICAL_AND_HEALTH');
    expect(normalizeBusinessVertical('AUTO'), 'AUTO');
    expect(normalizeBusinessVertical('NOT_A_CODE'), isNull);
    expect(normalizeBusinessVertical(''), isNull);
    for (final code in kMetaBusinessVerticals) {
      expect(normalizeBusinessVertical(code), code);
    }
  });

  test('vertical labels read as WhatsApp shows them', () {
    expect(businessVerticalLabel('AUTO'), 'Automotive');
    expect(businessVerticalLabel('RETAIL'), 'Shopping and retail');
  });

  test('a verification code is requested only while WhatsApp waits for one', () {
    expect(_sender('PENDING_REVIEW', 'PENDING_VERIFICATION').needsVerificationCode, isTrue);
    expect(_sender('PENDING_REVIEW', 'VERIFYING').needsVerificationCode, isFalse);
    expect(_sender('ACTIVE', 'ONLINE').needsVerificationCode, isFalse);
  });

  test('registration progress copy never names the provider', () {
    for (final raw in [
      'CREATING', 'PENDING', 'PENDING_VERIFICATION', 'VERIFYING',
      'TWILIO_REVIEW', 'ONLINE', 'ONLINE:UPDATING', 'OFFLINE', 'RETRY_PENDING',
    ]) {
      final label = registrationProgressLabel(raw);
      expect(label, isNotNull, reason: raw);
      expect(label!.toLowerCase(), isNot(contains('twilio')), reason: raw);
    }
    expect(registrationProgressLabel('SOMETHING_NEW'), isNull);
  });

  test('a bare website domain gets https and junk is refused', () {
    expect(normalizeWebsiteUrl(' test.corillo.do '), 'https://test.corillo.do');
    expect(normalizeWebsiteUrl('http://example.com'), 'http://example.com');
    expect(normalizeWebsiteUrl(''), '');
    expect(websiteUrlError(''), isNull);
    expect(websiteUrlError('test.corillo.do'), isNull);
    expect(websiteUrlError('https://example.com/shop?x=1'), isNull);
    expect(websiteUrlError('not a website'), isNotNull);
    expect(websiteUrlError('localhost'), isNotNull);
    expect(websiteUrlError('ftp://example.com'), isNotNull);
  });
}

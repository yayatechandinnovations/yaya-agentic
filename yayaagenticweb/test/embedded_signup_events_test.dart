import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_senders/embedded_signup/embedded_signup_events.dart';

void main() {
  const goodOrigin = 'https://www.facebook.com';

  Map<String, dynamic> signup(String event, Map<String, dynamic> data) =>
      {'type': 'WA_EMBEDDED_SIGNUP', 'event': event, 'data': data};

  group('origin validation', () {
    test('rejects non-allowlisted origins, including lookalikes', () {
      for (final origin in [
        'https://evil.example',
        'https://www.facebook.com.evil.example', // substring lookalike
        'https://facebook.com', // not on the exact allowlist
        'http://www.facebook.com', // wrong scheme
        '',
      ]) {
        final result = parseEmbeddedSignupMessage(
          origin: origin,
          data: jsonEncode(signup('FINISH',
              {'waba_id': '111', 'phone_number_id': '222'})),
        );
        expect(result, isA<SignupIgnored>(), reason: 'origin=$origin');
        expect((result as SignupIgnored).reason, 'origin_not_allowed');
      }
    });

    test('accepts both canonical Meta origins', () {
      for (final origin in kMetaMessageOrigins) {
        final result = parseEmbeddedSignupMessage(
          origin: origin,
          data: jsonEncode(signup('FINISH', {'waba_id': '111'})),
        );
        expect(result, isA<SignupFinish>(), reason: 'origin=$origin');
      }
    });
  });

  group('FINISH', () {
    test('captures waba_id and phone_number_id', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: jsonEncode(signup('FINISH',
            {'waba_id': '123456789012345', 'phone_number_id': '98765'})),
      ) as SignupFinish;
      expect(result.wabaId, '123456789012345');
      expect(result.phoneNumberId, '98765');
      expect(result.wabaOnly, isFalse);
    });

    test('FINISH_ONLY_WABA yields null phoneNumberId and wabaOnly flag', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: jsonEncode(signup('FINISH_ONLY_WABA', {'waba_id': '111'})),
      ) as SignupFinish;
      expect(result.phoneNumberId, isNull);
      expect(result.wabaOnly, isTrue);
    });

    test('FINISH without waba_id becomes an error, not a success', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: jsonEncode(signup('FINISH', {})),
      );
      expect(result, isA<SignupError>());
    });

    test('accepts an already-decoded map payload', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: signup('FINISH', {'waba_id': '111'}),
      );
      expect(result, isA<SignupFinish>());
    });
  });

  group('CANCEL / ERROR', () {
    test('CANCEL surfaces the abandoned step', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: jsonEncode(signup('CANCEL', {'current_step': 'BUSINESS_INFO'})),
      ) as SignupCancel;
      expect(result.currentStep, 'BUSINESS_INFO');
    });

    test('ERROR surfaces the message', () {
      final result = parseEmbeddedSignupMessage(
        origin: goodOrigin,
        data: jsonEncode(signup('ERROR', {'error_message': 'boom'})),
      ) as SignupError;
      expect(result.message, 'boom');
    });
  });

  group('noise tolerance', () {
    test('non-JSON strings are ignored', () {
      final result = parseEmbeddedSignupMessage(
          origin: goodOrigin, data: 'not json at all');
      expect(result, isA<SignupIgnored>());
    });

    test('unrelated postMessage payloads are ignored', () {
      final result = parseEmbeddedSignupMessage(
          origin: goodOrigin,
          data: jsonEncode({'type': 'something_else', 'event': 'FINISH'}));
      expect(result, isA<SignupIgnored>());
    });

    test('unknown future events are ignored, not errors', () {
      final result = parseEmbeddedSignupMessage(
          origin: goodOrigin,
          data: jsonEncode(signup('SOME_FUTURE_EVENT', {'waba_id': '111'})));
      expect(result, isA<SignupIgnored>());
    });

    test('non-string non-map data is ignored', () {
      final result =
          parseEmbeddedSignupMessage(origin: goodOrigin, data: 42);
      expect(result, isA<SignupIgnored>());
    });
  });
}

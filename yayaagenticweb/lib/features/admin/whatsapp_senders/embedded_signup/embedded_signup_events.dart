/// Pure parsing/validation of Meta Embedded Signup `window.postMessage`
/// events. Kept free of `dart:html` so the logic is unit-testable on the VM;
/// the web launcher feeds raw (origin, data) pairs through [parseEmbeddedSignupMessage].
library;

import 'dart:convert';

/// Exact origins Meta's Embedded Signup popup posts from. An explicit
/// allowlist — never a substring check — per Meta's security guidance.
const Set<String> kMetaMessageOrigins = {
  'https://www.facebook.com',
  'https://web.facebook.com',
};

sealed class EmbeddedSignupEvent {
  const EmbeddedSignupEvent();
}

/// FINISH — the customer completed signup; `phoneNumberId` may be null when
/// Meta reported FINISH_ONLY_WABA (WABA created, phone step deferred).
class SignupFinish extends EmbeddedSignupEvent {
  const SignupFinish({required this.wabaId, this.phoneNumberId, required this.wabaOnly});
  final String wabaId;
  final String? phoneNumberId;
  final bool wabaOnly;
}

class SignupCancel extends EmbeddedSignupEvent {
  const SignupCancel({this.currentStep});
  final String? currentStep;
}

class SignupError extends EmbeddedSignupEvent {
  const SignupError({this.message});
  final String? message;
}

/// Message was not for us (foreign origin, unrelated payload, malformed
/// JSON). Callers must silently drop these.
class SignupIgnored extends EmbeddedSignupEvent {
  const SignupIgnored(this.reason);
  final String reason;
}

/// Validate + parse one `message` event. [data] may be the raw JSON string
/// Meta posts or an already-decoded map.
EmbeddedSignupEvent parseEmbeddedSignupMessage({
  required String origin,
  required Object? data,
  Set<String> allowedOrigins = kMetaMessageOrigins,
}) {
  if (!allowedOrigins.contains(origin)) {
    return const SignupIgnored('origin_not_allowed');
  }

  Map<String, dynamic> payload;
  if (data is String) {
    try {
      final decoded = jsonDecode(data);
      if (decoded is! Map) return const SignupIgnored('not_an_object');
      payload = Map<String, dynamic>.from(decoded);
    } catch (_) {
      return const SignupIgnored('not_json');
    }
  } else if (data is Map) {
    payload = Map<String, dynamic>.from(data);
  } else {
    return const SignupIgnored('unsupported_data_type');
  }

  if (payload['type'] != 'WA_EMBEDDED_SIGNUP') {
    return const SignupIgnored('not_embedded_signup');
  }

  final event = payload['event']?.toString();
  final inner = payload['data'] is Map
      ? Map<String, dynamic>.from(payload['data'] as Map)
      : const <String, dynamic>{};

  switch (event) {
    case 'FINISH':
    case 'FINISH_ONLY_WABA':
      final wabaId = inner['waba_id']?.toString();
      if (wabaId == null || wabaId.isEmpty) {
        return const SignupError(
            message: 'Meta signup finished but returned no business account id.');
      }
      final phoneNumberId = inner['phone_number_id']?.toString();
      return SignupFinish(
        wabaId: wabaId,
        phoneNumberId:
            phoneNumberId == null || phoneNumberId.isEmpty ? null : phoneNumberId,
        wabaOnly: event == 'FINISH_ONLY_WABA',
      );
    case 'CANCEL':
      return SignupCancel(currentStep: inner['current_step']?.toString());
    case 'ERROR':
      return SignupError(message: inner['error_message']?.toString());
    default:
      return const SignupIgnored('unknown_event');
  }
}

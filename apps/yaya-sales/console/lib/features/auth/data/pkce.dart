import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// A PKCE (RFC 7636) verifier/challenge pair. The verifier is kept client-side
/// and presented at the token exchange; only its S256 challenge travels in the
/// authorization request.
class Pkce {
  const Pkce(this.verifier, this.challenge);

  final String verifier;
  final String challenge;

  factory Pkce.generate() {
    final rng = Random.secure();
    final bytes = List<int>.generate(32, (_) => rng.nextInt(256));
    final verifier = _b64Url(bytes);
    final challenge = _b64Url(sha256.convert(utf8.encode(verifier)).bytes);
    return Pkce(verifier, challenge);
  }

  static String _b64Url(List<int> bytes) => base64UrlEncode(bytes).replaceAll('=', '');
}

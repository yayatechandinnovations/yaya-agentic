import 'dart:convert';

/// Reads the tenant-bearing `org` claim from a JWT payload WITHOUT verifying
/// it — display-plane convenience only (prefilling the tenant); the sales API
/// enforces the real operator-tenant binding server-side.
String? orgClaimOf(String jwt) {
  final parts = jwt.split('.');
  if (parts.length != 3) return null;
  try {
    final payload =
        jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(parts[1]))));
    final org = payload is Map<String, dynamic> ? payload['org'] : null;
    if (org is String && org.isNotEmpty) return org;
    if (org is List && org.isNotEmpty) return org.first.toString();
    return null;
  } on FormatException {
    return null;
  }
}

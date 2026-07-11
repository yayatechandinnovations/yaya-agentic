import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yaya_sales_console/core/storage/jwt_claims.dart';

String _jwt(Map<String, dynamic> payload) {
  String b64(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${b64({'alg': 'none'})}.${b64(payload)}.sig';
}

void main() {
  test('reads a scalar org claim', () {
    expect(orgClaimOf(_jwt({'org': 'org_abc123', 'sub': 'u1'})), 'org_abc123');
  });

  test('takes the first entry of a list-valued org claim', () {
    expect(orgClaimOf(_jwt({'org': ['org_a', 'org_b']})), 'org_a');
  });

  test('missing/empty claim and malformed tokens are null, not errors', () {
    expect(orgClaimOf(_jwt({'sub': 'u1'})), isNull);
    expect(orgClaimOf(_jwt({'org': ''})), isNull);
    expect(orgClaimOf('not-a-jwt'), isNull);
    expect(orgClaimOf('a.%%%.c'), isNull);
  });
}

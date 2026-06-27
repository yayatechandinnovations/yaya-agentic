import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/api_keys/application/api_key_roles.dart';

/// Pure-logic coverage for the create-role bound and the create/revoke gate
/// (design §6.2). The server stays authoritative — this just keeps the UI's
/// affordances honest.
void main() {
  group('mintableRoles', () {
    test('OWNER can mint every workspace tier', () {
      expect(mintableRoles('OWNER'), ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']);
    });

    test('ADMIN cannot mint OWNER', () {
      expect(mintableRoles('ADMIN'), ['ADMIN', 'MEMBER', 'VIEWER']);
      expect(mintableRoles('ADMIN'), isNot(contains('OWNER')));
    });

    test('PLATFORM_ADMIN caps at OWNER (no platform tier in keys)', () {
      expect(mintableRoles('PLATFORM_ADMIN'),
          ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']);
    });

    test('MEMBER and VIEWER can mint only at/below themselves', () {
      expect(mintableRoles('MEMBER'), ['MEMBER', 'VIEWER']);
      expect(mintableRoles('VIEWER'), ['VIEWER']);
    });
  });

  group('roleAtLeast (create/revoke gate is ADMIN+)', () {
    test('OWNER and ADMIN can manage', () {
      expect(roleAtLeast('OWNER', 'ADMIN'), isTrue);
      expect(roleAtLeast('ADMIN', 'ADMIN'), isTrue);
    });

    test('MEMBER and VIEWER cannot manage', () {
      expect(roleAtLeast('MEMBER', 'ADMIN'), isFalse);
      expect(roleAtLeast('VIEWER', 'ADMIN'), isFalse);
    });
  });
}

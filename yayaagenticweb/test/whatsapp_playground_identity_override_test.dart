import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/models/admin/channel_binding.dart';

/// Defends the Flutter-side shape of the `defaultPrincipalPolicyOverride`
/// payload sent from the WhatsApp playground. The wire format MUST be the
/// same `DefaultPrincipalPolicyWire` shape the admin form sends, so the
/// backend has a single Jackson decoder for both paths.
void main() {
  group('playground identity override', () {
    test('Anonymous override has the kind discriminator at the top level', () {
      final wire = DefaultPrincipalPolicyWire.anonymous();
      final json = wire.toJson();
      expect(json['kind'], equals('Anonymous'));
    });

    test('PhoneAsPrincipal override carries scopes and claims', () {
      final wire = DefaultPrincipalPolicyWire.phoneAsPrincipal(
        additionalScopes: const ['order:read', 'shipment:read'],
        additionalClaims: const {'role': 'tester'},
      );
      final json = wire.toJson();
      expect(json['kind'], equals('PhoneAsPrincipal'));
      expect(json['additionalScopes'], containsAll(<String>[
        'order:read',
        'shipment:read',
      ]));
      expect(json['additionalClaims'], containsPair('role', 'tester'));
    });

    test('LookupPrincipal override carries resolverId and denyOnUnknown', () {
      final wire = DefaultPrincipalPolicyWire.lookupPrincipal(
        resolverId: 'retail-customer-lookup',
        denyOnUnknown: false,
      );
      final json = wire.toJson();
      expect(json['kind'], equals('LookupPrincipal'));
      expect(json['resolverId'], equals('retail-customer-lookup'));
      expect(json['denyOnUnknown'], isFalse);
    });

    test('reservedClaimKeys are the same set the playground validates against',
        () {
      // The dialog's `_addClaim` rejects reserved keys client-side using this
      // set. If it ever drifts from the backend's set, the playground would
      // accept a payload the backend later rejects with 400.
      expect(
        DefaultPrincipalPolicyWire.reservedClaimKeys,
        equals({
          'phone',
          'channel.sender',
          'channel.kind',
          'locale',
          'twilioContactSid',
        }),
      );
    });
  });
}

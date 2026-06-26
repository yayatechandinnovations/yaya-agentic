import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/models/admin/channel_binding.dart';

/// Defends the Flutter wire model for `defaultPrincipalPolicy`. Mirrors the
/// backend's `SessionPolicyJsonRoundTripTest` so the contract is policed from
/// both ends.
void main() {
  group('DefaultPrincipalPolicyWire', () {
    test('Anonymous is the default for legacy payloads without the field', () {
      final policy = SessionPolicyWire.fromJson({
        'idleTimeout': 'PT24H',
        'newSessionKeywords': <String>[],
        'endSessionKeywords': <String>[],
        'greetOnReengagement': false,
        'exposeEndUserPhoneToProfile': true,
        // no defaultPrincipalPolicy
      });
      expect(policy.defaultPrincipalPolicy.kind, equals('Anonymous'));
    });

    test('PhoneAsPrincipal round-trips scopes and claims losslessly', () {
      final original = SessionPolicyWire.defaults().copyWith(
        defaultPrincipalPolicy: DefaultPrincipalPolicyWire.phoneAsPrincipal(
          additionalScopes: const ['order:read', 'shipment:read'],
          additionalClaims: const {'role': 'guest', 'tier': 'starter'},
        ),
      );
      final encoded = original.toJson();
      final decoded = SessionPolicyWire.fromJson(encoded);
      expect(decoded.defaultPrincipalPolicy.kind, equals('PhoneAsPrincipal'));
      expect(decoded.defaultPrincipalPolicy.additionalScopes,
          containsAll(<String>['order:read', 'shipment:read']));
      expect(decoded.defaultPrincipalPolicy.additionalClaims,
          containsPair('role', 'guest'));
      expect(decoded.defaultPrincipalPolicy.additionalClaims,
          containsPair('tier', 'starter'));
    });

    test('LookupPrincipal round-trips resolver id and denyOnUnknown', () {
      final original = SessionPolicyWire.defaults().copyWith(
        defaultPrincipalPolicy: DefaultPrincipalPolicyWire.lookupPrincipal(
          resolverId: 'retail-customer-lookup',
          denyOnUnknown: false,
        ),
      );
      final decoded = SessionPolicyWire.fromJson(original.toJson());
      expect(decoded.defaultPrincipalPolicy.kind, equals('LookupPrincipal'));
      expect(decoded.defaultPrincipalPolicy.resolverId,
          equals('retail-customer-lookup'));
      expect(decoded.defaultPrincipalPolicy.json['denyOnUnknown'], isFalse);
    });

    test('reservedClaimKeys matches the backend list', () {
      // If this set drifts from the backend's PrincipalPolicyMaterializer
      // RESERVED_CLAIM_KEYS, a client-side accept can be silently rejected by
      // the server (or vice versa). Both ends must agree on this exact set.
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

    test('LookupPrincipal preserves denyOnUnknown when toggled off', () {
      // Default constructor sets denyOnUnknown=true; admin flips it to false.
      final original = SessionPolicyWire.defaults().copyWith(
        defaultPrincipalPolicy: DefaultPrincipalPolicyWire.lookupPrincipal(
          resolverId: 'retail-customer-lookup',
          denyOnUnknown: false,
        ),
      );
      final decoded = SessionPolicyWire.fromJson(original.toJson());
      expect(decoded.defaultPrincipalPolicy.kind, equals('LookupPrincipal'));
      expect(decoded.defaultPrincipalPolicy.resolverId,
          equals('retail-customer-lookup'));
      expect(decoded.defaultPrincipalPolicy.json['denyOnUnknown'], isFalse);
    });
  });

  group('PhonePrincipalResolverSummary', () {
    test('decodes id and description from the admin endpoint payload', () {
      final s = PhonePrincipalResolverSummary.fromJson({
        'id': 'retail-customer-lookup',
        'description': 'Maps phones to retail customer IDs',
      });
      expect(s.id, equals('retail-customer-lookup'));
      expect(s.description, equals('Maps phones to retail customer IDs'));
    });

    test('tolerates a missing description field', () {
      final s = PhonePrincipalResolverSummary.fromJson({'id': 'r1'});
      expect(s.id, equals('r1'));
      expect(s.description, isEmpty);
    });
  });

  group('humanLabel (sanity from W-A1, kept for parity)', () {
    test('reflects the policy shape', () {
      expect(DefaultPrincipalPolicyWire.anonymous().humanLabel(),
          equals('Anonymous (phone only)'));

      expect(
          DefaultPrincipalPolicyWire.phoneAsPrincipal().humanLabel(),
          equals('Phone as principal'));

      expect(
          DefaultPrincipalPolicyWire.phoneAsPrincipal(
            additionalScopes: const ['order:read'],
            additionalClaims: const {'role': 'guest'},
          ).humanLabel(),
          equals('Phone as principal · +2'));

      expect(
          DefaultPrincipalPolicyWire.lookupPrincipal(
                  resolverId: 'my-resolver')
              .humanLabel(),
          equals('Lookup principal · my-resolver'));
    });
  });
}

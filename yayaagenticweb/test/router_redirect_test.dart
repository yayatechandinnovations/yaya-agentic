import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/app/router.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';

/// Regression coverage for the redirect-during-AuthChecking bug: a user
/// clicking a verify link in their inbox arrives anonymous. The bootstrap
/// /v1/auth/me call hasn't returned yet, so authProvider is in
/// {@link AuthChecking}. The router MUST let token-bearer routes mount
/// immediately; bouncing to /loading would strip the `?token=` query
/// and dead-end the user at /login.
void main() {
  group('AuthChecking', () {
    test('lets /verify mount immediately', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthChecking(),
          matchedLocation: '/verify',
        ),
        isNull,
      );
    });

    test('lets /reset-password mount immediately', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthChecking(),
          matchedLocation: '/reset-password',
        ),
        isNull,
      );
    });

    test('lets /accept-invitation mount immediately', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthChecking(),
          matchedLocation: '/accept-invitation',
        ),
        isNull,
      );
    });

    test('lets /signup, /forgot-password, /verify/pending mount immediately',
        () {
      for (final loc in ['/signup', '/forgot-password', '/verify/pending']) {
        expect(
          resolveAuthRedirect(
            auth: const AuthChecking(),
            matchedLocation: loc,
          ),
          isNull,
          reason: '$loc must not bounce to /loading',
        );
      }
    });

    test('private routes still bounce to /loading during AuthChecking', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthChecking(),
          matchedLocation: '/playground',
        ),
        equals('/loading'),
      );
    });

    test('already-on-/loading stays put during AuthChecking', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthChecking(),
          matchedLocation: '/loading',
        ),
        isNull,
      );
    });
  });

  group('AuthUnauthenticated', () {
    test('/verify is public and passes through', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthUnauthenticated(),
          matchedLocation: '/verify',
        ),
        isNull,
      );
    });

    test('private routes redirect to /login with returnTo', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthUnauthenticated(),
          matchedLocation: '/admin/profiles',
        ),
        equals('/login?returnTo=%2Fadmin%2Fprofiles'),
      );
    });

    test('/loading routes to /login with /playground returnTo', () {
      expect(
        resolveAuthRedirect(
          auth: const AuthUnauthenticated(),
          matchedLocation: '/loading',
        ),
        equals('/login?returnTo=%2Fplayground'),
      );
    });
  });

  group('AuthAuthenticated', () {
    test('/verify still passes through (juggling tabs after sign-in)', () {
      expect(
        resolveAuthRedirect(
          auth: AuthAuthenticated(_op()),
          matchedLocation: '/verify',
        ),
        isNull,
      );
    });

    test('/login bounces to /playground', () {
      expect(
        resolveAuthRedirect(
          auth: AuthAuthenticated(_op()),
          matchedLocation: '/login',
        ),
        equals('/playground'),
      );
    });

    test('platform-only routes bounce non-platform-admins to /playground', () {
      expect(
        resolveAuthRedirect(
          auth: AuthAuthenticated(_op(platformAdmin: false)),
          matchedLocation: '/admin/tenants',
        ),
        equals('/playground'),
      );
    });

    test('platform-only routes pass through for platform admins', () {
      expect(
        resolveAuthRedirect(
          auth: AuthAuthenticated(_op(platformAdmin: true)),
          matchedLocation: '/admin/tenants',
        ),
        isNull,
      );
    });
  });
}

Operator _op({bool platformAdmin = false}) => Operator(
      subject: 'op-1',
      displayName: 'Test',
      source: 'user-password',
      platformAdmin: platformAdmin,
    );

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/auth_bindings/auth_bindings_screen.dart';
import 'package:yayaagenticweb/features/admin/auth_resolvers/auth_resolvers_screen.dart';
import 'package:yayaagenticweb/features/admin/identity_routing/identity_routing_screen.dart';
import 'package:yayaagenticweb/features/admin/jwt_secrets/jwt_secrets_screen.dart';
import 'package:yayaagenticweb/features/admin/oidc_issuers/oidc_issuers_screen.dart';
import 'package:yayaagenticweb/models/admin/auth_binding.dart';
import 'package:yayaagenticweb/models/admin/auth_resolver.dart';
import 'package:yayaagenticweb/models/admin/identity_profile_mapping.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/tenant_jwt_secret.dart';
import 'package:yayaagenticweb/models/admin/tenant_oidc_issuer.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';
import 'package:yayaagenticweb/ui/theme.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final bindingRequests = <AuthBindingRequest>[];
  final oidcRequests = <TenantOidcIssuerRequest>[];
  final jwtUpdates = <(int, TenantJwtSecretRequest)>[];
  final jwtCreates = <TenantJwtSecretRequest>[];
  final mappingUpdates = <(int, IdentityProfileMappingRequest)>[];
  final mappingCreates = <IdentityProfileMappingRequest>[];
  final resolverCreates = <AuthResolverRequest>[];
  final probes = <String>[];

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async => [
        for (final v in [1, 2])
          ProfileResponse(
            id: 'merchant-agent',
            version: v,
            tenant: tenant,
            displayName: 'Merchant agent',
            introOneLiner: '',
            systemPromptFragment: '',
            authBindingId: 'retail-oidc',
          ),
      ];

  @override
  Future<List<AuthBindingResponse>> listAuthBindings({String tenant = 'default'}) async => [
        AuthBindingResponse(id: 'retail-oidc', tenant: tenant, authenticatorRef: 'oidc', authorizerChain: const ['ScopeAuthorizer']),
      ];

  @override
  Future<AuthAvailability> availableAuth() async => const AuthAvailability(
        authenticators: ['oidc', 'shared-secret-jwt', 'noop'],
        authorizers: ['OwnershipAuthorizer', 'ScopeAuthorizer'],
      );

  @override
  Future<AuthBindingResponse> createAuthBinding(AuthBindingRequest req) async {
    bindingRequests.add(req);
    return AuthBindingResponse(id: req.id, tenant: req.tenant, authenticatorRef: req.authenticatorRef, authorizerChain: req.authorizerChain);
  }

  @override
  Future<List<TenantOidcIssuerResponse>> listTenantOidcIssuers(String tenantId) async => const [];

  @override
  Future<TenantOidcIssuerResponse> createTenantOidcIssuer(String tenantId, TenantOidcIssuerRequest body) async {
    oidcRequests.add(body);
    return TenantOidcIssuerResponse(id: 1, tenantId: tenantId, iss: body.iss, jwksUri: body.jwksUri, audiences: body.audiences);
  }

  @override
  Future<List<TenantJwtSecretResponse>> listTenantJwtSecrets(String tenantId) async => [
        TenantJwtSecretResponse(
          id: 7,
          tenantId: tenantId,
          iss: 'smart-lottery-identity',
          audiences: const ['yaya'],
          principalKind: 'network-operator',
          requiredClaims: const {'org': 'acme'},
        ),
      ];

  @override
  Future<TenantJwtSecretResponse> createTenantJwtSecret(String tenantId, TenantJwtSecretRequest body) async {
    jwtCreates.add(body);
    return TenantJwtSecretResponse(id: 8, tenantId: tenantId, iss: body.iss);
  }

  @override
  Future<TenantJwtSecretResponse> updateTenantJwtSecret(String tenantId, int id, TenantJwtSecretRequest body) async {
    jwtUpdates.add((id, body));
    return TenantJwtSecretResponse(id: id, tenantId: tenantId, iss: body.iss, audiences: body.audiences);
  }

  @override
  Future<List<IdentityProfileMappingResponse>> listIdentityProfileMappings(String tenantId) async => [
        IdentityProfileMappingResponse(
          id: 3,
          tenantId: tenantId,
          claimName: 'roles',
          claimValue: 'merchant',
          profileId: 'merchant-agent',
          profileVersion: null,
          priority: 10,
        ),
      ];

  @override
  Future<IdentityProfileMappingResponse> createIdentityProfileMapping(String tenantId, IdentityProfileMappingRequest body) async {
    mappingCreates.add(body);
    return IdentityProfileMappingResponse(
        id: 4, tenantId: tenantId, claimName: body.claimName, claimValue: body.claimValue, profileId: body.profileId);
  }

  @override
  Future<IdentityProfileMappingResponse> updateIdentityProfileMapping(String tenantId, int id, IdentityProfileMappingRequest body) async {
    mappingUpdates.add((id, body));
    return IdentityProfileMappingResponse(
        id: id, tenantId: tenantId, claimName: body.claimName, claimValue: body.claimValue, profileId: body.profileId,
        profileVersion: body.profileVersion, priority: body.priority);
  }

  @override
  Future<AuthResolverListResponse> listAuthResolvers(String tenantId) async => AuthResolverListResponse(
        resolvers: [
          AuthResolverResponse(
            id: 5,
            tenantId: tenantId,
            name: 'acme-lookup',
            kind: 'HTTP',
            httpPath: '/yaya/resolve',
            serviceAuthKind: 'SIGNED_JWT',
            serviceSecretMasked: '••••abcd',
            status: 'ACTIVE',
          ),
        ],
        registeredBeanNames: const [],
      );

  @override
  Future<AuthResolverResponse> createAuthResolver(String tenantId, AuthResolverRequest body) async {
    resolverCreates.add(body);
    return AuthResolverResponse(id: 6, tenantId: tenantId, name: body.name, kind: body.kind, status: 'ACTIVE');
  }

  @override
  Future<AuthResolverProbeResult> testAuthResolver(String tenantId, String name, AuthResolverProbeInput input) async {
    probes.add('$name ${input.callerPhone}');
    return AuthResolverProbeResult(
      ok: true,
      transport: 'HTTP',
      url: 'https://acme.example/yaya/resolve',
      elapsedMs: 42,
      tokenReceived: true,
      verification: AuthResolverVerification(ok: true, issuer: 'smart-lottery-identity', subject: 'cust-1'),
    );
  }
}

/// The list screen with a button that pushes [editor] as a plain page. The
/// kit's showYSheet frame trips a debug-only semantics assertion
/// (scopesRoute without explicitChildNodes) under the test binding, so
/// editors are exercised outside it; the list underneath keeps its providers
/// alive for the duplicate checks.
Widget _app(_FakeAdminApi api, Widget screen, {Widget? editor}) {
  final router = GoRouter(
    initialLocation: '/screen',
    routes: [
      GoRoute(
        path: '/screen',
        builder: (_, _) => Scaffold(
          body: screen,
          floatingActionButton: editor == null
              ? null
              : Builder(
                  builder: (ctx) => FloatingActionButton(
                    key: const Key('open-editor'),
                    onPressed: () => Navigator.of(ctx).push(MaterialPageRoute<void>(builder: (_) => Scaffold(body: editor))),
                    child: const Icon(Icons.edit),
                  ),
                ),
        ),
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp.router(theme: buildTheme(Brightness.light), routerConfig: router),
  );
}

Future<void> _pumpEditor(WidgetTester tester, _FakeAdminApi api, Widget screen, Widget editor) async {
  await tester.pumpWidget(_app(api, screen, editor: editor));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('open-editor')));
  await tester.pumpAndSettle();
}

Future<void> _tapButton(WidgetTester tester, String label) async {
  final f = find.widgetWithText(FilledButton, label);
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Future<void> _enter(WidgetTester tester, String label, String text) async {
  final field = find.descendant(
    of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
    matching: find.byType(TextField),
  );
  await tester.ensureVisible(field.first);
  await tester.enterText(field.first, text);
  await tester.pump();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  group('Auth bindings', () {
    testWidgets('a blank binding shows inline errors and is not saved; a valid one keeps the chain order', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const AuthBindingsScreen(), const AuthBindingEditorSheet());
      expect(find.text('New auth binding'), findsWidgets);
      await _tapButton(tester, 'Create binding');
      expect(find.text('Enter a binding ID.'), findsWidgets);
      expect(find.text('Pick how users of this binding sign in.'), findsWidgets);
      expect(find.textContaining('need attention'), findsOneWidget);
      expect(api.bindingRequests, isEmpty);

      await _enter(tester, 'Binding ID', 'retail-oidc');
      await _tapButton(tester, 'Create binding');
      expect(find.textContaining('already exists'), findsWidgets);
      expect(api.bindingRequests, isEmpty);

      await _enter(tester, 'Binding ID', 'partner-jwt');
      await tester.tap(find.text('Shared-secret JWT'));
      await tester.pump();
      await tester.tap(find.widgetWithText(ActionChip, 'Scopes'));
      await tester.pump();
      await tester.tap(find.widgetWithText(ActionChip, 'Ownership'));
      await tester.pump();
      await tester.tap(find.byTooltip('Move up').last);
      await tester.pump();
      await _tapButton(tester, 'Create binding');

      expect(api.bindingRequests, hasLength(1));
      final req = api.bindingRequests.single;
      expect(req.id, 'partner-jwt');
      expect(req.authenticatorRef, 'shared-secret-jwt');
      expect(req.authorizerChain, ['OwnershipAuthorizer', 'ScopeAuthorizer']);
    });
  });

  group('OIDC issuers', () {
    testWidgets('issuer and JWKS must be absolute URLs before anything is sent', (tester) async {
      final api = _FakeAdminApi();
      await tester.pumpWidget(_app(api, const OidcIssuersScreen(), editor: const OidcIssuerEditorSheet()));
      await tester.pumpAndSettle();
      expect(find.text('No trusted issuers yet'), findsOneWidget);
      await tester.tap(find.byKey(const Key('open-editor')));
      await tester.pumpAndSettle();

      await _enter(tester, 'Issuer', 'acme');
      await _enter(tester, 'JWKS URL', 'ftp://keys.acme.com/jwks');
      await _tapButton(tester, 'Trust issuer');
      expect(find.text('Enter a full URL, e.g. https://login.example.com/'), findsWidgets);
      expect(find.text('Use an https:// URL.'), findsWidgets);
      expect(api.oidcRequests, isEmpty);

      await _enter(tester, 'Issuer', 'https://acme.eu.auth0.com/');
      await _enter(tester, 'JWKS URL', 'https://acme.eu.auth0.com/.well-known/jwks.json');
      await _enter(tester, 'Accepted audiences', 'https://api.acme.com\n\nacme-chat');
      await _tapButton(tester, 'Trust issuer');
      expect(api.oidcRequests.single.audiences, ['https://api.acme.com', 'acme-chat']);
    });
  });

  group('JWT secrets', () {
    testWidgets('a short secret is refused inline', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const JwtSecretsScreen(), const JwtSecretEditorSheet());

      await _tapButton(tester, 'Add secret');
      expect(find.text('Enter the shared signing secret.'), findsWidgets);
      await _enter(tester, 'Issuer', 'partner-idp');
      await _enter(tester, 'Signing secret', 'too-short');
      await _tapButton(tester, 'Add secret');
      expect(find.textContaining('Use at least 32 characters'), findsWidgets);
      expect(api.jwtCreates, isEmpty);
    });

    testWidgets('editing metadata keeps the stored secret and the tenant pins', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const JwtSecretsScreen(), JwtSecretEditorSheet(existing: (await api.listTenantJwtSecrets('t1')).single));

      expect(find.text('Stored and hidden. Leave blank to keep it; entering a value rotates it when you save.'), findsOneWidget);
      await _enter(tester, 'Accepted audiences', 'yaya\nyaya-chat');
      await _tapButton(tester, 'Save changes');

      final (id, body) = api.jwtUpdates.single;
      expect(id, 7);
      expect(body.secret, isNull);
      expect(body.toJson().containsKey('secret'), isFalse);
      expect(body.audiences, ['yaya', 'yaya-chat']);
      expect(body.principalKind, 'network-operator');
      expect(body.requiredClaims, {'org': 'acme'});
    });

    testWidgets('rotating asks the operator to type the issuer first', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const JwtSecretsScreen(), JwtSecretEditorSheet(existing: (await api.listTenantJwtSecrets('t1')).single));

      await _enter(tester, 'New signing secret', 'a-brand-new-secret-that-is-long-enough-1234');
      await _tapButton(tester, 'Rotate secret');
      expect(find.textContaining('stop working'), findsOneWidget);
      final confirm = find.widgetWithText(FilledButton, 'Rotate secret').last;
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull);
      await tester.enterText(find.byType(TextField).last, 'smart-lottery-identity');
      await tester.pump();
      await tester.tap(confirm);
      await tester.pumpAndSettle();
      expect(api.jwtUpdates.single.$2.secret, 'a-brand-new-secret-that-is-long-enough-1234');
    });
  });

  group('Identity routing', () {
    testWidgets('a blank route shows inline errors and is not saved', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const IdentityRoutingScreen(), const IdentityRouteEditorSheet());

      expect(find.text('A token matches when its claim…'), findsOneWidget);
      await _tapButton(tester, 'Add route');
      expect(find.text('Enter the claim name, e.g. roles.'), findsWidgets);
      expect(find.text('Pick the profile these users should get.'), findsWidgets);
      expect(api.mappingCreates, isEmpty);
    });

    testWidgets('editing an unpinned route keeps it unpinned', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const IdentityRoutingScreen(),
          IdentityRouteEditorSheet(existing: (await api.listIdentityProfileMappings('t1')).single));

      await _enter(tester, 'Priority', '20');
      await _tapButton(tester, 'Save route');
      final (id, body) = api.mappingUpdates.single;
      expect(id, 3);
      expect(body.profileVersion, isNull);
      expect(body.toJson()['profileVersion'], isNull);
      expect(body.priority, 20);
    });
  });

  group('Auth resolvers', () {
    testWidgets('a bad path and a missing secret are caught before saving', (tester) async {
      final api = _FakeAdminApi();
      await _pumpEditor(tester, api, const AuthResolversScreen(), const AuthResolverEditorSheet());

      await _enter(tester, 'Name', 'acme-loyalty');
      await _enter(tester, 'Webhook path', 'yaya/resolve');
      await _tapButton(tester, 'Create resolver');
      expect(find.text('Start with / — the path is added to the tenant’s host base URL.'), findsWidgets);
      expect(find.text('Enter the secret your webhook expects for Signed JWT.'), findsWidgets);
      expect(api.resolverCreates, isEmpty);
    });

    testWidgets('the Test probe runs from the list and shows the verification', (tester) async {
      final api = _FakeAdminApi();
      await tester.pumpWidget(_app(api, const AuthResolversScreen()));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(TextButton, 'Test'));
      await tester.pumpAndSettle();
      await _tapButton(tester, 'Run test');
      expect(api.probes, ['acme-lookup +15555550100']);
      expect(find.text('Passed'), findsOneWidget);
      expect(find.text('Verified'), findsOneWidget);
      expect(find.text('cust-1'), findsOneWidget);
    });
  });
}

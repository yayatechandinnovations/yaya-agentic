import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/api/api_config.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/audit/audit_screen.dart';
import 'package:yayaagenticweb/features/admin/operator_auth/operator_auth_screen.dart';
import 'package:yayaagenticweb/features/admin/tenants/tenants_screen.dart';
import 'package:yayaagenticweb/features/api_keys/data/api_keys_api.dart';
import 'package:yayaagenticweb/features/api_keys/presentation/api_keys_screen.dart';
import 'package:yayaagenticweb/features/api_keys/presentation/create_api_key_dialog.dart';
import 'package:yayaagenticweb/features/api_keys/presentation/show_secret_panel.dart';
import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/features/auth/models/operator.dart';
import 'package:yayaagenticweb/features/settings/settings_screen.dart';
import 'package:yayaagenticweb/features/workspaces/application/workspaces_controller.dart';
import 'package:yayaagenticweb/features/workspaces/data/members_api.dart';
import 'package:yayaagenticweb/features/workspaces/data/workspaces_api.dart';
import 'package:yayaagenticweb/features/workspaces/presentation/create_workspace_dialog.dart';
import 'package:yayaagenticweb/features/workspaces/presentation/invite_member_dialog.dart';
import 'package:yayaagenticweb/features/workspaces/presentation/workspace_list_screen.dart';
import 'package:yayaagenticweb/features/workspaces/presentation/workspace_settings_screen.dart';
import 'package:yayaagenticweb/models/admin/audit.dart';
import 'package:yayaagenticweb/models/admin/member.dart';
import 'package:yayaagenticweb/models/admin/api_key.dart';
import 'package:yayaagenticweb/models/admin/invitation.dart';
import 'package:yayaagenticweb/models/admin/operator_auth_strategies.dart';
import 'package:yayaagenticweb/models/admin/tenant.dart';
import 'package:yayaagenticweb/models/admin/workspace.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';
import 'package:yayaagenticweb/ui/theme.dart';

const _acme = TenantResponse(
  id: 'acme',
  displayName: 'Acme',
  status: 'ACTIVE',
  hostBaseUrl: 'https://api.acme.com',
  settings: {'locale': 'es-DO', 'flags': {'beta': true}},
  defaultRecordingStrategyId: 4,
);

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final created = <TenantRequest>[];
  final patched = <(String, TenantRequest)>[];
  final delegatePuts = <(DelegateView, String?, bool)>[];
  StrategiesResponse strategies = const StrategiesResponse(
    bootstrap: BootstrapView(enabled: true, username: 'root'),
    delegate: DelegateView(
      enabled: false,
      request: RequestShape(body: RequestBody()),
      success: SuccessCriteria(),
      identity: IdentityMapping(subjectPath: r'$.user.email', attributesPath: r'$.user'),
      failure: FailureMapping(reasonPath: r'$.error.code'),
    ),
  );

  @override
  Future<List<TenantResponse>> listTenants({String? status}) async => const [_acme];

  @override
  Future<TenantHealthResponse> tenantHealth(String id) async => TenantHealthResponse(
      tenantId: id, status: 'ACTIVE', hostBaseUrlSet: true, authBindingResolves: true, recordingStrategyResolves: true, dependencyCount: 0);

  @override
  Future<TenantResponse> createTenant(TenantRequest req) async {
    created.add(req);
    return TenantResponse(id: req.id!, displayName: req.displayName!, status: 'ACTIVE');
  }

  @override
  Future<TenantResponse> patchTenant(String id, TenantRequest req) async {
    patched.add((id, req));
    return _acme.copyWith(displayName: req.displayName ?? _acme.displayName);
  }

  @override
  Future<StrategiesResponse> getStrategies() async => strategies;

  final auditQueries = <String?>[];

  @override
  Future<AuthzAuditPage> searchAuthzAudit({
    String tenant = 'default',
    String? decision,
    String? principal,
    String? toolId,
    int page = 0,
    int pageSize = 30,
  }) async {
    auditQueries.add(decision);
    return AuthzAuditPage(
      items: [
        AuthzAuditEntry(id: 1, tenant: tenant, decision: 'DENY', toolId: 'orders_refund', principal: 'cust-1',
            userReason: 'You can only refund your own orders.', auditReason: 'owner mismatch', policyTrace: const {'rule': 'OwnershipAuthorizer'}),
      ],
      page: page,
      pageSize: pageSize,
      total: 31,
    );
  }

  @override
  Future<StrategiesResponse> putDelegateStrategy(DelegateView delegate, {String? sharedSecret, bool confirmPermissive = false}) async {
    delegatePuts.add((delegate, sharedSecret, confirmPermissive));
    strategies = strategies.copyWith(delegate: delegate.copyWith(sharedSecretPresent: true));
    return strategies;
  }
}

class _FakeWorkspacesApi extends WorkspacesApi {
  _FakeWorkspacesApi() : super(Dio());
  final creates = <String>[];
  final patches = <String?>[];

  @override
  Future<List<WorkspaceListItem>> listMine() async => const [
        WorkspaceListItem(workspace: Workspace(id: 'acme', code: 'acme', displayName: 'Acme', status: 'ACTIVE'), role: 'ADMIN'),
      ];

  @override
  Future<Workspace> create(String displayName,
      {String? code, String? hostBaseUrl, List<String>? hostBaseUrlAllowlist, List<String>? inboundOriginAllowlist}) async {
    creates.add(displayName);
    return Workspace(id: code ?? 'x', code: code ?? 'x', displayName: displayName);
  }

  @override
  Future<Workspace> patch(String idOrCode,
      {String? displayName, String? hostBaseUrl, List<String>? hostBaseUrlAllowlist, List<String>? inboundOriginAllowlist}) async {
    patches.add(hostBaseUrl);
    return Workspace(id: idOrCode, code: idOrCode, displayName: displayName ?? '', hostBaseUrl: hostBaseUrl);
  }
}

class _FakeMembersApi extends WorkspaceMembersApi {
  _FakeMembersApi() : super(Dio());
  final invites = <String>[];

  @override
  Future<List<Member>> listMembers(String workspaceCode) async => const [
        Member(userId: 'u-1', email: 'me@acme.com', displayName: 'Me', role: 'ADMIN'),
        Member(userId: 'u-2', email: 'owner@acme.com', displayName: 'Olga', role: 'OWNER'),
      ];

  @override
  Future<Invitation> invite({required String workspaceCode, required String email, required String role}) async {
    invites.add('$email:$role');
    return Invitation(id: 'i1', workspaceId: workspaceCode, email: email, role: role);
  }
}

class _FakeKeysApi extends ApiKeysApi {
  _FakeKeysApi() : super(Dio());
  final creates = <String>[];

  @override
  Future<ApiKeyCreated> create(String workspaceCode, {required String name, required String role, String? expiresAt}) async {
    creates.add('$name:$role');
    return ApiKeyCreated(id: 'k1', name: name, keyPrefix: 'yk_ab', role: role, secret: 'yk_secret');
  }

  @override
  Future<List<ApiKey>> list(String workspaceCode) async => const [
        ApiKey(id: 'k9', name: 'CI deploy', keyPrefix: 'yk_9', role: 'MEMBER', status: 'active'),
        ApiKey(id: 'k8', name: 'Old', keyPrefix: 'yk_8', role: 'VIEWER', status: 'revoked', platformScope: true),
      ];
}

class _SignedIn extends AuthController {
  @override
  AuthState build() => const AuthAuthenticated(Operator(
        subject: 'op-1',
        displayName: 'Op',
        source: 'user-password',
        platformAdmin: true,
        attributes: {'user_id': 'u-1'},
      ));
}

class _Fakes {
  final admin = _FakeAdminApi();
  final workspaces = _FakeWorkspacesApi();
  final members = _FakeMembersApi();
  final keys = _FakeKeysApi();
}

/// [screen] as the page, with a button that pushes [editor] as a plain
/// route — the kit's sheet frame trips a debug-only semantics assertion
/// under the test binding (see auth_admin_screens_test.dart).
Widget _app(_Fakes f, Widget screen, {Widget? editor}) {
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
      GoRoute(path: '/workspaces', builder: (_, _) => const Scaffold(body: Text('workspaces'))),
    ],
  );
  return ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => f.admin),
      workspacesApiProvider.overrideWith((ref) async => f.workspaces),
      workspaceMembersApiProvider.overrideWith((ref) async => f.members),
      apiKeysApiProvider.overrideWith((ref) async => f.keys),
      authProvider.overrideWith(_SignedIn.new),
      currentTenantOrNull.overrideWithValue('acme'),
    ],
    child: MaterialApp.router(theme: buildTheme(Brightness.light), routerConfig: router),
  );
}

Future<void> _pump(WidgetTester tester, _Fakes f, Widget screen, {Widget? editor}) async {
  await tester.pumpWidget(_app(f, screen, editor: editor));
  await tester.pumpAndSettle();
  if (editor != null) {
    await tester.tap(find.byKey(const Key('open-editor')));
    await tester.pumpAndSettle();
  }
}

Future<void> _tapFilled(WidgetTester tester, String label) async {
  final b = find.widgetWithText(FilledButton, label);
  await tester.ensureVisible(b.last);
  await tester.tap(b.last);
  await tester.pumpAndSettle();
}

Future<void> _enter(WidgetTester tester, String label, String text) async {
  final field = find.descendant(
    of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
    matching: find.byType(TextField),
  );
  await tester.ensureVisible(field.last);
  await tester.enterText(field.last, text);
  await tester.pump();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  group('Tenants', () {
    testWidgets('registering checks the ID, host URL and allowlists inline and sends nothing until valid', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const TenantsScreen(), editor: const TenantEditorSheet());

      await _tapFilled(tester, 'Register tenant');
      expect(find.text('Enter a tenant ID.'), findsWidgets);
      expect(find.text('Enter a display name.'), findsWidgets);
      expect(find.textContaining('Enter the host base URL'), findsWidgets);
      expect(find.textContaining('need attention'), findsOneWidget);

      await _enter(tester, 'Tenant ID', 'Acme_Retail');
      await _enter(tester, 'Display name', 'Acme Retail');
      await _enter(tester, 'Host base URL', 'http://api.acme.com');
      await _enter(tester, 'Allowed browser origins', 'https://shop.acme.com/path');
      await _tapFilled(tester, 'Register tenant');
      expect(find.textContaining('lowercase letters, digits or dashes'), findsWidgets);
      expect(find.textContaining('https:// while “Require HTTPS” is on'), findsWidgets);
      expect(find.textContaining('Line 1 (https://shop.acme.com/path)'), findsWidgets);
      expect(f.admin.created, isEmpty);

      await _enter(tester, 'Tenant ID', 'acme');
      await _tapFilled(tester, 'Register tenant');
      expect(find.textContaining('already taken'), findsWidgets);
      expect(f.admin.created, isEmpty);

      await _enter(tester, 'Tenant ID', 'acme-retail');
      await _enter(tester, 'Host base URL', 'https://api.acme-retail.com');
      await _enter(tester, 'Allowed browser origins', 'https://shop.acme.com');
      await _tapFilled(tester, 'Register tenant');
      expect(f.admin.created, hasLength(1));
      final req = f.admin.created.single;
      expect(req.id, 'acme-retail');
      expect(req.hostBaseUrl, 'https://api.acme-retail.com');
      expect(req.inboundOriginAllowlist, ['https://shop.acme.com']);
      expect(req.requireHttps, isTrue);
    });

    testWidgets('saving an existing tenant keeps its settings map and default ids untouched', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const TenantsScreen(), editor: const TenantEditorSheet(existing: _acme));
      await _enter(tester, 'Display name', 'Acme Corp');
      await _tapFilled(tester, 'Save tenant');

      expect(f.admin.patched, hasLength(1));
      final (id, req) = f.admin.patched.single;
      expect(id, 'acme');
      expect(req.displayName, 'Acme Corp');
      // PATCH replaces `settings` wholesale — it must go back unchanged, not as {}.
      expect(req.settings, {'locale': 'es-DO', 'flags': {'beta': true}});
      // null = "leave as is" for the default ids on PATCH.
      expect(req.defaultRecordingStrategyId, isNull);
      expect(req.defaultAuthenticatorBindingId, isNull);
    });
  });

  group('Operator auth', () {
    testWidgets('turning the delegate on needs an https URL and a secret before anything is saved', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const OperatorAuthScreen());

      await tester.tap(find.text('Use the HTTP delegate'));
      await tester.pump();
      await _tapFilled(tester, 'Save delegate settings');
      expect(find.textContaining('Enter the login endpoint URL'), findsWidgets);
      expect(find.textContaining('Enter a shared secret'), findsWidgets);
      expect(f.admin.delegatePuts, isEmpty);

      await _enter(tester, 'Login endpoint URL', 'http://login.acme.com/check');
      await _tapFilled(tester, 'Save delegate settings');
      expect(find.textContaining('https:// while “Require HTTPS” is on'), findsWidgets);
      expect(f.admin.delegatePuts, isEmpty);

      await _enter(tester, 'Login endpoint URL', 'https://login.acme.com/check');
      final secret = find.descendant(
        of: find.byWidgetPredicate((w) => w is YSecretField && w.label == 'Shared secret'),
        matching: find.byType(TextField),
      );
      await tester.enterText(secret, 's3cret');
      await tester.pump();
      await _tapFilled(tester, 'Save delegate settings');

      expect(f.admin.delegatePuts, hasLength(1));
      final (view, sent, permissive) = f.admin.delegatePuts.single;
      expect(view.enabled, isTrue);
      expect(view.url, 'https://login.acme.com/check');
      expect(sent, 's3cret');
      expect(permissive, isFalse);
      // Fields in collapsed sections the operator never opened survive the save.
      expect(view.identity.subjectPath, r'$.user.email');
      expect(view.identity.attributesPath, r'$.user');
      expect(view.failure.reasonPath, r'$.error.code');
      expect(view.success.statusIn, [200, 204]);
    });
  });

  group('Settings', () {
    testWidgets('a backend URL that isn’t http(s) is refused at the field; theme cards switch the mode', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const SettingsScreen());

      await _enter(tester, 'Backend URL', 'localhost:8080');
      await _tapFilled(tester, 'Save settings');
      expect(find.text('Enter a full URL starting with https://'), findsWidgets);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString('yaya.backend.base_url'), isNull);

      await _enter(tester, 'Backend URL', 'https://agentic.acme.com');
      await _tapFilled(tester, 'Save settings');
      expect(prefs.getString('yaya.backend.base_url'), 'https://agentic.acme.com');

      await tester.tap(find.text('Dark'));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(SettingsScreen)));
      expect(container.read(themeModeProvider), ThemeMode.dark);
      expect(ApiConfig.defaultBaseUrl, isNotEmpty);
    });
  });

  group('Workspaces', () {
    testWidgets('creating a workspace with a reserved code is caught inline', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const SizedBox(), editor: const CreateWorkspaceDialog(asSheet: true));

      await _tapFilled(tester, 'Create workspace');
      expect(find.text('Enter a workspace name.'), findsWidgets);
      expect(f.workspaces.creates, isEmpty);

      await _enter(tester, 'Workspace name', 'Admin team');
      await tester.tap(find.text('Choose the workspace code myself'));
      await tester.pump();
      await _enter(tester, 'Workspace code', 'admin');
      await _tapFilled(tester, 'Create workspace');
      expect(find.textContaining('is reserved'), findsWidgets);
      expect(f.workspaces.creates, isEmpty);

      await _enter(tester, 'Workspace code', 'admin-team');
      await _tapFilled(tester, 'Create workspace');
      expect(f.workspaces.creates, ['Admin team']);
    });

    testWidgets('inviting needs a real email address', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const SizedBox(), editor: const InviteMemberSheet(workspaceCode: 'acme', workspaceName: 'Acme'));

      await _enter(tester, 'Email', 'ana');
      await _tapFilled(tester, 'Send invitation');
      expect(find.textContaining('Enter a full email address'), findsWidgets);
      expect(f.members.invites, isEmpty);

      await _enter(tester, 'Email', 'ana@acme.com');
      await tester.tap(find.text('Viewer'));
      await tester.pump();
      await _tapFilled(tester, 'Send invitation');
      expect(f.members.invites, ['ana@acme.com:VIEWER']);
    });

    testWidgets('general settings reject a malformed host URL before patching', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const WorkspaceSettingsScreen(code: 'acme'));
      expect(find.text('Acme'), findsWidgets);

      await _enter(tester, 'Host base URL', 'api.acme.com/v1?x=1');
      await _tapFilled(tester, 'Save workspace');
      expect(find.textContaining('no ? or #'), findsWidgets);
      expect(f.workspaces.patches, isEmpty);

      await _enter(tester, 'Host base URL', 'https://api.acme.com');
      await _tapFilled(tester, 'Save workspace');
      expect(f.workspaces.patches, ['https://api.acme.com']);
      final container = ProviderScope.containerOf(tester.element(find.byType(WorkspaceSettingsScreen)));
      expect(container.read(myWorkspacesProvider).hasValue, isTrue);
    });
  });

  group('API keys', () {
    testWidgets('a too-short name is refused inline and an admin can’t mint an owner key', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const SizedBox(), editor: const CreateApiKeySheet(workspaceCode: 'acme', creatorRole: 'ADMIN'));

      expect(find.text('Owner'), findsNothing);
      await _enter(tester, 'Name', 'ci');
      await _tapFilled(tester, 'Create key');
      expect(find.text('Use at least 3 characters.'), findsWidgets);
      expect(f.keys.creates, isEmpty);

      await _enter(tester, 'Name', 'CI deploy bot');
      await _tapFilled(tester, 'Create key');
      expect(f.keys.creates, ['CI deploy bot:MEMBER']);
    });

    testWidgets('the one-time secret can’t be dismissed until the operator says it’s stored', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, Builder(
        builder: (ctx) => Center(
          child: TextButton(
            onPressed: () => ShowSecretDialog.show(ctx, const ApiKeyCreated(id: 'k1', name: 'CI', keyPrefix: 'yk_ab', role: 'MEMBER', secret: 'yk_live_secret')),
            child: const Text('show'),
          ),
        ),
      ));
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      expect(find.text('yk_live_secret'), findsOneWidget);
      expect(find.textContaining('won’t see it again'), findsOneWidget);
      final done = find.widgetWithText(FilledButton, 'Done');
      expect(tester.widget<FilledButton>(done).onPressed, isNull);

      await tester.tap(find.byType(Checkbox));
      await tester.pump();
      expect(tester.widget<FilledButton>(done).onPressed, isNotNull);
      await tester.tap(done);
      await tester.pumpAndSettle();
      expect(find.text('yk_live_secret'), findsNothing);
    });
  });

  group('Lists render with data', () {
    testWidgets('tenants, workspaces, API keys, members and audit show their rows', (tester) async {
      final f = _Fakes();
      await _pump(tester, f, const TenantsScreen());
      expect(find.text('Acme'), findsWidgets);
      expect(find.text('Healthy'), findsOneWidget);

      await _pump(tester, f, const WorkspaceListScreen());
      expect(find.text('Acme'), findsWidgets);
      expect(find.text('Admin'), findsWidgets);

      await _pump(tester, f, const ApiKeysScreen());
      expect(find.text('CI deploy'), findsOneWidget);
      expect(find.text('Platform'), findsOneWidget);
      expect(find.text('Revoked'), findsWidgets);

      await _pump(tester, f, const WorkspaceSettingsScreen(code: 'acme'));
      await tester.tap(find.text('Members'));
      await tester.pumpAndSettle();
      expect(find.text('Me (you)'), findsOneWidget);
      expect(find.text('Olga'), findsOneWidget);

      await _pump(tester, f, const AuditScreen());
      expect(find.text('orders_refund'), findsOneWidget);
      expect(find.text('31 matching · page 1 of 2'), findsOneWidget);
      await tester.tap(find.widgetWithText(ChoiceChip, 'Deny'));
      await tester.pumpAndSettle();
      expect(f.admin.auditQueries.last, 'DENY');
    });
  });
}

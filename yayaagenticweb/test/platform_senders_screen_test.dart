import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/app/shell/nav_model.dart';
import 'package:yayaagenticweb/features/admin/platform_senders/platform_senders_screen.dart';
import 'package:yayaagenticweb/models/admin/platform_sender.dart';
import 'package:yayaagenticweb/models/admin/tenant.dart';

DioException _apiError(int status, String code, String message) {
  final options = RequestOptions(path: '/v1/admin/platform-senders');
  return DioException(
    requestOptions: options,
    response: Response(requestOptions: options, statusCode: status, data: {'error': code, 'message': message}),
  );
}

Map<String, dynamic> _platformSender({int assignments = 1}) => {
      'id': 'snd_p',
      'tenantId': null,
      'owner': 'PLATFORM',
      'phoneE164': '+15024874573',
      'displayName': 'Avisos',
      'approvedDisplayName': 'Avisos Smart Lottery',
      'businessProfile': {'legalName': 'SL', 'displayName': 'SL', 'contactEmail': 'a@b.co', 'vertical': 'OTHER'},
      'status': 'ACTIVE',
      'onboarding': {'status': 'ONLINE', 'twilioSenderSid': 'XE1'},
      'defaultLocale': 'es-DO',
      'unroutedReply': 'Solo avisos.',
      'assignmentCount': assignments,
    };

PlatformSenderAssignment _assignment() => PlatformSenderAssignment.fromJson({
      'id': 'wsa_1',
      'senderId': 'snd_p',
      'tenantId': 'banca',
      'label': 'Banca La Suerte',
      'status': 'ACTIVE',
      'dailyOutboundCap': 1000,
      'stats': {'templateSends24h': 12, 'optOuts7d': 2, 'members': 7},
    });

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final ends = <({String tenant, bool dryRun})>[];
  final assigns = <({String tenant, PlatformSenderAssignmentRequest body})>[];
  bool labelTaken = false;
  bool revokeRefused = false;
  bool revoked = false;

  @override
  Future<List<PlatformSenderResponse>> listPlatformSenders() async =>
      [PlatformSenderResponse.fromJson(_platformSender())];

  @override
  Future<List<PlatformSenderAssignment>> listPlatformSenderAssignments(String senderId) async => [_assignment()];

  @override
  Future<List<UnroutedInbound>> listPlatformSenderUnrouted(String senderId, {int limit = 50}) async => [
        const UnroutedInbound(messageSid: 'SM1', phoneMasked: '+1809•••1234', reason: 'not_routed', body: 'hola'),
      ];

  @override
  Future<List<TenantResponse>> listTenants({String? status}) async => const [
        TenantResponse(id: 'banca', displayName: 'Banca', status: 'ACTIVE'),
        TenantResponse(id: 'acme', displayName: 'Acme', status: 'ACTIVE'),
        TenantResponse(id: 'old', displayName: 'Old', status: 'SUSPENDED'),
      ];

  @override
  Future<PlatformSenderAssignment> putPlatformSenderAssignment(
      String senderId, String tenantId, PlatformSenderAssignmentRequest body) async {
    assigns.add((tenant: tenantId, body: body));
    if (labelTaken) throw _apiError(409, 'label_taken', 'label taken on this sender');
    return PlatformSenderAssignment.fromJson(
        {'id': 'wsa_2', 'senderId': senderId, 'tenantId': tenantId, 'label': body.label, 'status': 'ACTIVE'});
  }

  @override
  Future<AssignmentEndResult> endPlatformSenderAssignment(String senderId, String tenantId,
      {bool dryRun = false}) async {
    ends.add((tenant: tenantId, dryRun: dryRun));
    return AssignmentEndResult.fromJson({
      'dryRun': dryRun,
      'effects': {
        'systemSendersUnlinked': ['pos'],
        'bindingsDeactivated': 1,
        'keywordActionsDeleted': 2,
        'membersDeleted': 7,
        'sessionsClosed': 3,
        'parkedOutboundDropped': 0,
        'routesCleared': 4,
      },
    });
  }

  @override
  Future<void> revokePlatformSender(String senderId) async {
    if (revokeRefused) throw _apiError(409, 'sender_has_assignments', 'sender has live assignments');
    revoked = true;
  }
}

Widget _screen(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: PlatformSendersScreen())),
    );

Future<void> _openWorkspacesTab(WidgetTester tester) async {
  await tester.tap(find.text('Avisos Smart Lottery').first);
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(Tab, 'Workspaces'));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  test('«Platform senders» sits next to the shared templates and is platform-admin only', () {
    final platform = flattenNav(navEntries).map((m) => m.item).firstWhere((i) => i.location == '/admin/platform-senders');
    expect(platform.label, 'Platform senders');
    expect(platform.platformOnly, isTrue);
    expect(platformOnlyRoutes, contains('/admin/platform-senders'));
    final hidden = flattenNav(visibleNavEntries(platformAdmin: false)).map((m) => m.item.location);
    expect(hidden, isNot(contains('/admin/platform-senders')));
  });

  testWidgets('the list shows status, phone, approved name and how many workspaces use it', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi()));
    await tester.pumpAndSettle();
    expect(find.text('Avisos Smart Lottery'), findsOneWidget);
    expect(find.text('+15024874573'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);
    expect(find.byKey(const ValueKey('assignments-snd_p')), findsOneWidget);
  });

  testWidgets('the Workspaces tab lists label, status, cap, sends, opt-outs and members', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi()));
    await tester.pumpAndSettle();
    await _openWorkspacesTab(tester);
    final row = find.byKey(const ValueKey('assignment-banca'));
    expect(row, findsOneWidget);
    for (final text in ['Banca La Suerte', 'banca', '1000', '12', '2', '7']) {
      expect(find.descendant(of: row, matching: find.text(text)), findsWidgets, reason: text);
    }
  });

  testWidgets('ending an assignment previews the effects with a dry run, then ends it', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _openWorkspacesTab(tester);

    await tester.tap(find.byKey(const ValueKey('end-banca')));
    await tester.pumpAndSettle();

    // The preview runs on open — nothing has been ended yet.
    expect(api.ends.single.dryRun, isTrue);
    expect(find.byKey(const Key('end-effects')), findsOneWidget);
    expect(find.textContaining('Unlinks 1 system sender: pos'), findsOneWidget);
    expect(find.textContaining('Removes 7 registered members'), findsOneWidget);
    expect(find.textContaining('Closes 3 open conversations'), findsOneWidget);
    expect(find.textContaining('Opt-outs stay'), findsOneWidget);

    await tester.tap(find.byKey(const Key('end-confirm')));
    await tester.pumpAndSettle();
    expect(api.ends, hasLength(2));
    expect(api.ends.last, (tenant: 'banca', dryRun: false));
    expect(find.byKey(const Key('end-effects')), findsNothing);
  });

  testWidgets('cancelling after the preview ends nothing', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _openWorkspacesTab(tester);
    await tester.tap(find.byKey(const ValueKey('end-banca')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, 'Cancel'));
    await tester.pumpAndSettle();
    expect(api.ends.where((e) => !e.dryRun), isEmpty);
  });

  testWidgets('assigning a workspace: already-assigned and inactive workspaces aren’t offered; a taken label is '
      'flagged at the field', (tester) async {
    final api = _FakeAdminApi()..labelTaken = true;
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await _openWorkspacesTab(tester);

    await tester.tap(find.byKey(const Key('assign-workspace')));
    await tester.pumpAndSettle();
    expect(find.text('1000'), findsWidgets, reason: 'cap defaults to 1000');

    await tester.tap(find.byKey(const Key('assign-tenant')));
    await tester.pumpAndSettle();
    expect(find.text('Banca (banca)'), findsNothing);
    expect(find.text('Old (old)'), findsNothing);
    await tester.tap(find.text('Acme (acme)').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.descendant(of: find.byKey(const Key('assign-label')), matching: find.byType(TextField)),
        'Banca La Suerte');
    await tester.tap(find.byKey(const Key('assign-save')));
    await tester.pumpAndSettle();

    expect(api.assigns.single.tenant, 'acme');
    expect(api.assigns.single.body.toJson(), {'label': 'Banca La Suerte', 'dailyOutboundCap': 1000});
    expect(find.text('Another workspace on this number already uses this name. Pick a different one.'), findsOneWidget);
    expect(find.byType(AlertDialog), findsOneWidget, reason: 'the dialog stays open to fix the name');
  });

  testWidgets('removing a number still in use explains that assignments must end first', (tester) async {
    final api = _FakeAdminApi()..revokeRefused = true;
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Avisos Smart Lottery').first);
    await tester.pumpAndSettle();

    final remove = find.widgetWithText(TextButton, 'Remove');
    await tester.ensureVisible(remove);
    await tester.tap(remove);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).last, '+15024874573');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Remove sender'));
    await tester.pumpAndSettle();

    expect(api.revoked, isFalse);
    expect(find.byKey(const Key('sender-has-assignments')), findsOneWidget);
    expect(find.textContaining('End each one on the «Workspaces» tab first'), findsOneWidget);
  });

  testWidgets('the Unrouted tab lists what matched no workspace', (tester) async {
    await tester.pumpWidget(_screen(_FakeAdminApi()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Avisos Smart Lottery').first);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(Tab, 'Unrouted'));
    await tester.pumpAndSettle();
    expect(find.text('+1809•••1234'), findsOneWidget);
    expect(find.text('hola'), findsOneWidget);
  });
}

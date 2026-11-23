import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/forum/forum_screen.dart';
import 'package:yayaagenticweb/models/admin/chat_entry_point.dart';
import 'package:yayaagenticweb/models/admin/forum.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  bool enabled = true;
  final posted = <AdminForumPostRequest>[];
  final moderated = <({String postId, Map<String, dynamic> body})>[];
  final settingsWrites = <bool>[];

  @override
  Future<List<ChatEntryPointResponse>> listChatEntryPoints(String tenantId) async => [
        ChatEntryPointResponse.fromJson({
          'id': 'chat_1',
          'tenantId': tenantId,
          'handle': 'e_abc',
          'profileId': 'concierge',
          'displayName': 'Market',
          'status': 'ACTIVE',
        }),
      ];

  @override
  Future<ForumSettingsResponse> getForumSettings(String tenantId, String entryPointId) async =>
      ForumSettingsResponse(enabled: enabled);

  @override
  Future<ForumSettingsResponse> updateForumSettings(String tenantId, String entryPointId, {required bool enabled}) async {
    settingsWrites.add(enabled);
    this.enabled = enabled;
    return ForumSettingsResponse(enabled: enabled);
  }

  @override
  Future<List<AdminForumPostView>> listForumPosts(String tenantId, String entryPointId) async => [
        AdminForumPostView.fromJson({
          'id': 'p1',
          'authorKind': 'USER',
          'authorDisplay': 'Ana',
          'body': 'Buy cheap pills here',
          'pinned': false,
          'hidden': false,
          'replyCount': 2,
          'reportCount': 1,
        }),
      ];

  @override
  Future<List<ForumReportView>> listForumReports(String tenantId) async => [
        ForumReportView.fromJson({'id': 'r1', 'postId': 'p1', 'reason': 'Spam'}),
      ];

  @override
  Future<AdminForumPostView> createForumPost(String tenantId, String entryPointId, AdminForumPostRequest body) async {
    posted.add(body);
    return AdminForumPostView.fromJson({'id': 'p2', 'authorKind': 'OPERATOR', 'body': body.body});
  }

  @override
  Future<void> moderateForumPost(String tenantId, String postId, ForumModerationRequest body) async {
    moderated.add((postId: postId, body: body.toJson()));
  }
}

Widget _app(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: ForumScreen())),
    );

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  test('brand post rules mirror the backend', () {
    expect(validateForumBody('   '), isNotNull);
    expect(validateForumBody('x' * 2001), contains('2000'));
    expect(validateForumBody('https://a http://b https://c'), isNull);
    expect(validateForumBody('https://a http://b https://c HTTPS://d'), contains('at most 3 links'));
  });

  testWidgets('a brand post that breaks the rules is stopped at the field', (tester) async {
    final api = _FakeAdminApi();
    // Mounted on its own route: showYSheet's frame trips a semantics assertion
    // under the test binding (Semantics(scopesRoute) without explicitChildNodes).
    await tester.pumpWidget(ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: FilledButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => Scaffold(
                  body: BrandPostSheet(
                    tenant: 't1',
                    entryPoint: ChatEntryPointResponse.fromJson(const {'id': 'chat_1', 'displayName': 'Market', 'status': 'ACTIVE'}),
                  ),
                ),
              )),
              child: const Text('open composer'),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('open composer'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Publish post'));
    await tester.pumpAndSettle();
    expect(find.text('Write the post first.'), findsWidgets);
    expect(api.posted, isEmpty);

    await tester.enterText(find.byType(TextField).first, 'https://a https://b https://c https://d');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Publish post'));
    await tester.pumpAndSettle();
    expect(find.text('Posts can contain at most 3 links — this one has 4.'), findsWidgets);
    expect(api.posted, isEmpty);

    await tester.enterText(find.byType(TextField).first, 'Open late on Friday!');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Publish post'));
    await tester.pumpAndSettle();
    expect(api.posted.single.body, 'Open late on Friday!');
    expect(find.text('open composer'), findsOneWidget);
  });

  testWidgets('hiding is immediate with undo; deleting asks first', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api));
    await tester.pumpAndSettle();
    expect(find.text('Buy cheap pills here'), findsOneWidget);

    await tester.tap(find.byTooltip('Hide from members'));
    await tester.pumpAndSettle();
    expect(api.moderated.single.body, {'hidden': true});
    expect(find.text('Undo'), findsOneWidget);

    await tester.tap(find.byTooltip('Delete post'));
    await tester.pumpAndSettle();
    expect(find.text('Delete this post by Ana?'), findsOneWidget);
    expect(find.textContaining('along with its 2 replies'), findsOneWidget);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Cancel'));
    await tester.pumpAndSettle();
    expect(api.moderated, hasLength(1));

    await tester.tap(find.byTooltip('Delete post'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Delete post'));
    await tester.pumpAndSettle();
    expect(api.moderated.last.body, {'delete': true});
  });

  testWidgets('turning the forum off confirms, then saves', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_app(api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Show the forum in this chat'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Turn off forum'));
    await tester.pumpAndSettle();
    expect(find.text('Turn off the forum for Market?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Turn off forum').last);
    await tester.pumpAndSettle();
    expect(api.settingsWrites, [false]);
  });
}

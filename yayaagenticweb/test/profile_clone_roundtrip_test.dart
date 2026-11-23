import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/profiles/profile_editor_screen.dart';
import 'package:yayaagenticweb/features/admin/profiles/profiles_screen.dart';
import 'package:yayaagenticweb/models/admin/auth_binding.dart';
import 'package:yayaagenticweb/models/admin/capability.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/chat_entry_point.dart';
import 'package:yayaagenticweb/models/admin/identity_profile_mapping.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/start_flow.dart';
import 'package:yayaagenticweb/models/admin/tool.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

/// The owner agent profile as the admin API returns it: gated to staff
/// principals through `metadata.principalKinds`, carrying a `metadata.policy`,
/// a store binding, a second extension kind the console has no editor for, and
/// a top-level field this console build has no model field for.
Map<String, dynamic> _ownerProfile() => {
      'id': 'owner-agent',
      'version': 3,
      'tenant': 't1',
      'displayName': 'Owner agent',
      'introOneLiner': 'I help the owner run the business.',
      'systemPromptFragment': 'You assist the business owner.',
      'capabilities': ['ops'],
      'authBindingId': null,
      'language': 'fr',
      'metadata': {
        'principalKinds': ['staff'],
        'policy': {
          'maxDiscountPct': 10,
          'allow': ['refund'],
        },
      },
      'status': 'ACTIVE',
      'createdAt': '2026-11-01T10:00:00Z',
      'extensions': {
        'sales.store': {
          'storeId': 'store-1',
          'checkoutTool': 'checkout',
          'cartEnabled': true,
          'currency': 'DOP',
        },
        'ops.whatsapp': {
          'sender': 'main',
          'templates': ['undo'],
        },
      },
      'defaultStartFlowId': null,
      'model': null,
      'maxToolRounds': 8,
      'metered': true,
      'fieldAddedByANewerServer': {'keep': true},
    };

/// Lists the owner profile parsed exactly as the real client parses it, and
/// records the body each save would POST.
class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  final posts = <Map<String, dynamic>>[];
  final baseVersions = <int?>[];

  /// Thrown by the next save instead of answering.
  Object? failWith;

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async =>
      [ProfileResponse.fromJson(_ownerProfile())];

  @override
  Future<List<CapabilityResponse>> listCapabilities({String tenant = 'default'}) async =>
      const [CapabilityResponse(id: 'ops', version: 2, tenant: 't1', label: 'Operations')];

  @override
  Future<List<AuthBindingResponse>> listAuthBindings({String tenant = 'default'}) async =>
      const [];

  @override
  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async => const [];

  @override
  Future<List<ToolResponse>> listTools({String tenant = 'default'}) async => const [];

  @override
  Future<List<IdentityProfileMappingResponse>> listIdentityProfileMappings(String tenantId) async => const [];

  @override
  Future<List<ChannelBindingResponse>> listChannelBindings(String tenantId) async => const [];

  @override
  Future<List<ChatEntryPointResponse>> listChatEntryPoints(String tenantId) async => const [];

  @override
  Future<ProfileResponse> createProfile(ProfileRequest req, {int? baseVersion}) async {
    final fail = failWith;
    if (fail != null) {
      failWith = null;
      throw fail;
    }
    final sent =
        Map<String, dynamic>.from(jsonDecode(jsonEncode(req.toWireJson())) as Map);
    posts.add(sent);
    baseVersions.add(baseVersion);
    return ProfileResponse.fromJson({..._ownerProfile(), ...sent, 'version': 4});
  }
}

/// Records what the real [AdminApi] puts on the wire.
class _RecordingAdapter implements HttpClientAdapter {
  final bodies = <Map<String, dynamic>>[];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final sent =
        Map<String, dynamic>.from(jsonDecode(jsonEncode(options.data)) as Map);
    bodies.add(sent);
    return ResponseBody.fromString(
        jsonEncode({..._ownerProfile(), ...sent, 'version': 4}), 201,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        });
  }

  @override
  void close({bool force = false}) {}
}

Future<_FakeAdminApi> _pumpConsole(WidgetTester tester, String location) async {
  tester.view.physicalSize = const Size(1600, 3000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  SharedPreferences.setMockInitialValues({});

  final api = _FakeAdminApi();
  final router = GoRouter(initialLocation: location, routes: [
    GoRoute(path: '/admin/profiles', builder: (_, _) => const Scaffold(body: ProfilesScreen())),
    GoRoute(
      path: '/admin/profiles/new',
      builder: (_, s) => Scaffold(body: ProfileEditorScreen(duplicateOf: s.uri.queryParameters['from'])),
    ),
    GoRoute(
      path: '/admin/profiles/:id',
      builder: (_, s) => Scaffold(body: ProfileEditorScreen(profileId: s.pathParameters['id'])),
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return api;
}

/// Opens the owner profile from the list, the way an operator does.
Future<_FakeAdminApi> _openOwnerProfile(WidgetTester tester) async {
  final api = await _pumpConsole(tester, '/admin/profiles');
  await tester.tap(find.text('Owner agent'));
  await tester.pumpAndSettle();
  return api;
}

Finder _field(String label) => find.descendant(
      of: find.byWidgetPredicate((w) => w is YTextField && w.label == label),
      matching: find.byType(TextField),
    );

Future<void> _tapButton(WidgetTester tester, String label) async {
  final button = find.widgetWithText(FilledButton, label);
  await tester.ensureVisible(button);
  await tester.tap(button);
  await tester.pumpAndSettle();
}

Future<void> _expandAdvanced(WidgetTester tester) async {
  final header = find.text('Advanced').last;
  await tester.ensureVisible(header);
  await tester.tap(header);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('publishing a new version saves every field the form does not edit',
      (tester) async {
    final backend = await _openOwnerProfile(tester);

    // Edit one thing the form does expose.
    await tester.enterText(_field('Display name'), 'Owner agent (v4)');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Publish v4');

    expect(backend.posts, hasLength(1));
    expect(backend.baseVersions.single, 3);
    final sent = backend.posts.single;

    expect(sent['displayName'], 'Owner agent (v4)');
    expect(sent['tenant'], 't1');
    expect(sent['id'], 'owner-agent');

    // The gate and the policy survive.
    final metadata = sent['metadata'] as Map<String, dynamic>;
    expect(metadata['principalKinds'], ['staff']);
    expect(metadata['policy'], {
      'maxDiscountPct': 10,
      'allow': ['refund'],
    });

    // So does the extension the form has no editor for, and the store
    // payload key the store controls do not cover.
    final extensions = sent['extensions'] as Map<String, dynamic>;
    expect(extensions['ops.whatsapp'], {
      'sender': 'main',
      'templates': ['undo'],
    });
    expect(extensions['sales.store'], {
      'storeId': 'store-1',
      'checkoutTool': 'checkout',
      'cartEnabled': true,
      'currency': 'DOP',
    });

    // Fields the form exposes but the operator did not touch.
    expect(sent['language'], 'fr');
    expect(sent['capabilities'], ['ops']);
    expect(sent['maxToolRounds'], 8);
    expect(sent['metered'], true);
    // The server keeps a runtime field a save leaves out, so the form's
    // empty model is sent as "" (the deployment's model), never as null.
    expect(sent['model'], '');

    // A field this console has no model for goes back as received; the ones
    // the server assigns do not.
    expect(sent['fieldAddedByANewerServer'], {'keep': true});
    expect(sent.containsKey('version'), isFalse);
    expect(sent.containsKey('status'), isFalse);
    expect(sent.containsKey('createdAt'), isFalse);
  });

  testWidgets('emptying the tool rounds of an existing profile saves the default, not the old value',
      (tester) async {
    final backend = await _openOwnerProfile(tester);

    await _expandAdvanced(tester);
    await tester.enterText(_field('Max tool rounds per turn'), '');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Publish v4');

    expect(backend.posts.single['maxToolRounds'], 5);
  });

  testWidgets('turning the store off removes only the store binding',
      (tester) async {
    final backend = await _openOwnerProfile(tester);

    final toggle = find.text('Sell from a yaya-sales store');
    await tester.ensureVisible(toggle);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Publish v4');

    final sent = backend.posts.single;
    expect((sent['extensions'] as Map).keys, ['ops.whatsapp']);
    expect((sent['metadata'] as Map)['principalKinds'], ['staff']);
  });

  testWidgets('a new profile opened after editing another carries nothing over',
      (tester) async {
    final backend = await _openOwnerProfile(tester);

    final cancel = find.widgetWithText(OutlinedButton, 'Cancel');
    await tester.ensureVisible(cancel);
    await tester.tap(cancel);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'New profile'));
    await tester.pumpAndSettle();

    await tester.enterText(_field('Profile ID'), 'fresh');
    await tester.enterText(_field('Display name'), 'Fresh');
    await tester.enterText(_field('Intro line'), 'Hello.');
    await tester.enterText(_field('Instructions for this role'), 'Be brief.');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Create profile');

    final sent = backend.posts.single;
    expect(sent['id'], 'fresh');
    expect(sent['metadata'], isEmpty);
    expect(sent['extensions'], isNull);
    expect(sent['maxToolRounds'], isNull);
    expect(sent.containsKey('fieldAddedByANewerServer'), isFalse);
    expect(backend.baseVersions.single, isNull);
  });

  testWidgets('a duplicate keeps what the form does not edit under its new ID',
      (tester) async {
    final backend = await _pumpConsole(tester, '/admin/profiles/new?from=owner-agent');

    await tester.enterText(_field('Profile ID'), 'owner-copy');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Create profile');

    final sent = backend.posts.single;
    expect(sent['id'], 'owner-copy');
    expect(sent['displayName'], 'Owner agent (copy)');
    expect((sent['metadata'] as Map)['principalKinds'], ['staff']);
    expect((sent['extensions'] as Map).keys, containsAll(['ops.whatsapp', 'sales.store']));
    expect(sent['fieldAddedByANewerServer'], {'keep': true});
    expect(backend.baseVersions.single, isNull);
  });

  testWidgets('missing required fields and bad tool rounds show inline and block the save',
      (tester) async {
    final backend = await _pumpConsole(tester, '/admin/profiles/new');

    await _expandAdvanced(tester);
    await tester.enterText(_field('Max tool rounds per turn'), '40');
    await tester.enterText(_field('Profile ID'), 'owner-agent');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Create profile');

    expect(backend.posts, isEmpty);
    expect(find.textContaining('need attention'), findsWidgets);
    expect(find.text('Enter a display name.'), findsWidgets);
    expect(find.text('Enter an intro line.'), findsWidgets);
    expect(find.text('Enter the instructions for this profile.'), findsWidgets);
    expect(find.text('Use a number from 1 to 16.'), findsWidgets);
    expect(find.textContaining('A profile with this ID already exists'), findsWidgets);
  });

  testWidgets('a model the server refuses is shown under the model field',
      (tester) async {
    final backend = await _openOwnerProfile(tester);
    backend.failWith = DioException(
      requestOptions: RequestOptions(path: '/v1/admin/profiles'),
      response: Response(
        requestOptions: RequestOptions(path: '/v1/admin/profiles'),
        statusCode: 400,
        data: {'error': 'model_not_allowed', 'message': "profile.model 'gpt-x' is not on this deployment's allow-list"},
      ),
      type: DioExceptionType.badResponse,
    );

    await _expandAdvanced(tester);
    await tester.enterText(_field('Model'), 'gpt-x');
    await tester.pumpAndSettle();
    await _tapButton(tester, 'Publish v4');

    expect(backend.posts, isEmpty);
    expect(find.textContaining('isn’t on the deployment’s allow-list'), findsWidgets);
  });

  test('the admin client posts the carried fields with the request', () async {
    final adapter = _RecordingAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'http://admin.test'))
      ..httpClientAdapter = adapter;
    final source = ProfileResponse.fromJson(_ownerProfile());

    await AdminApi(dio).createProfile(ProfileRequest(
      tenant: source.tenant,
      id: source.id,
      displayName: source.displayName,
      introOneLiner: source.introOneLiner,
      systemPromptFragment: source.systemPromptFragment,
      metadata: source.metadata,
      extensions: source.extensions,
      carried: source.raw,
    ));

    final sent = adapter.bodies.single;
    expect((sent['metadata'] as Map)['principalKinds'], ['staff']);
    expect((sent['extensions'] as Map).containsKey('ops.whatsapp'), isTrue);
    expect(sent['fieldAddedByANewerServer'], {'keep': true});
    expect(sent.containsKey('version'), isFalse);
  });

  test('a request field wins over a carried key of the same name', () {
    final wire = const ProfileRequest(
      tenant: 't1',
      id: 'p',
      displayName: 'Edited',
      introOneLiner: 'i',
      systemPromptFragment: 's',
      carried: {'displayName': 'Stale', 'version': 9, 'extra': 1},
    ).toWireJson();
    expect(wire['displayName'], 'Edited');
    expect(wire['extra'], 1);
    expect(wire.containsKey('version'), isFalse);
    expect(wire.containsKey('carried'), isFalse);
  });
}

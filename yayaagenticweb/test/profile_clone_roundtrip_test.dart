import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/profiles/profiles_screen.dart';
import 'package:yayaagenticweb/models/admin/auth_binding.dart';
import 'package:yayaagenticweb/models/admin/capability.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';
import 'package:yayaagenticweb/models/admin/start_flow.dart';

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

  @override
  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async =>
      [ProfileResponse.fromJson(_ownerProfile())];

  @override
  Future<List<CapabilityResponse>> listCapabilities({String tenant = 'default'}) async =>
      const [];

  @override
  Future<List<AuthBindingResponse>> listAuthBindings({String tenant = 'default'}) async =>
      const [];

  @override
  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async => const [];

  @override
  Future<ProfileResponse> createProfile(ProfileRequest req) async {
    final sent =
        Map<String, dynamic>.from(jsonDecode(jsonEncode(req.toWireJson())) as Map);
    posts.add(sent);
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

Future<_FakeAdminApi> _openCloneOfOwnerProfile(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1600, 3000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  final api = _FakeAdminApi();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      adminApiProvider.overrideWith((ref) async => api),
      currentTenantOrNull.overrideWithValue('t1'),
    ],
    child: const MaterialApp(home: Scaffold(body: ProfilesScreen())),
  ));
  await tester.pumpAndSettle();

  await tester.tap(find.text('owner-agent@3'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Clone & edit'));
  await tester.pumpAndSettle();
  return api;
}

Future<void> _save(WidgetTester tester) async {
  final save = find.widgetWithText(FilledButton, 'Save as v4');
  await tester.ensureVisible(save);
  await tester.tap(save);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('clone & edit saves every field the form does not edit',
      (tester) async {
    final backend = await _openCloneOfOwnerProfile(tester);

    // Edit one thing the form does expose.
    await tester.enterText(
        find.widgetWithText(TextField, 'display name'), 'Owner agent (v4)');
    await _save(tester);

    expect(backend.posts, hasLength(1));
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

  testWidgets('emptying the tool rounds of a clone saves the default, not the old value',
      (tester) async {
    final backend = await _openCloneOfOwnerProfile(tester);

    await tester.enterText(
        find.widgetWithText(TextField, 'max tool rounds per turn (optional)'), '');
    await _save(tester);

    expect(backend.posts.single['maxToolRounds'], 5);
  });

  testWidgets('turning the store off removes only the store binding',
      (tester) async {
    final backend = await _openCloneOfOwnerProfile(tester);

    final toggle = find.widgetWithText(SwitchListTile, 'Commerce (storefront)');
    await tester.ensureVisible(toggle);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    await _save(tester);

    final sent = backend.posts.single;
    expect((sent['extensions'] as Map).keys, ['ops.whatsapp']);
    expect((sent['metadata'] as Map)['principalKinds'], ['staff']);
  });

  testWidgets('a new profile after a cancelled clone carries nothing over',
      (tester) async {
    final backend = await _openCloneOfOwnerProfile(tester);

    final cancel = find.widgetWithText(TextButton, 'Cancel');
    await tester.ensureVisible(cancel);
    await tester.tap(cancel);
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextField, 'id'), 'fresh');
    await tester.enterText(
        find.widgetWithText(TextField, 'display name'), 'Fresh');
    await tester.enterText(
        find.widgetWithText(TextField, 'intro one-liner'), 'Hello.');
    await tester.enterText(
        find.widgetWithText(TextField, 'system prompt fragment'), 'Be brief.');
    final save = find.widgetWithText(FilledButton, 'Save');
    await tester.ensureVisible(save);
    await tester.tap(save);
    await tester.pumpAndSettle();

    final sent = backend.posts.single;
    expect(sent['id'], 'fresh');
    expect(sent['metadata'], isEmpty);
    expect(sent['extensions'], isNull);
    expect(sent.containsKey('fieldAddedByANewerServer'), isFalse);
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

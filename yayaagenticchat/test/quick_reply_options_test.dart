import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/chat_api.dart';
import 'package:yayaagenticchat/data/credential_store.dart';
import 'package:yayaagenticchat/data/providers.dart';
import 'package:yayaagenticchat/features/chat/chat_controller.dart';
import 'package:yayaagenticchat/models/chat_message.dart';
import 'package:yayaagenticchat/models/entry_point_card.dart';

/// One message the controller sent: the legible text + the structured selection.
class _Sent {
  _Sent(this.text, this.selection);
  final String text;
  final Map<String, Object?>? selection;
}

/// Records every send and replays a scripted SSE turn for it.
class _FakeApi extends ChatApi {
  final sent = <_Sent>[];
  List<SseEvent> nextTurn = const [];

  @override
  Future<EntryPointCard> resolve(String handle) async => const EntryPointCard(
        handle: 'e_ops',
        displayName: 'Ops',
        logoUrl: '/l',
        authMode: 'ANONYMOUS_ONLY',
        defaultLocale: 'es',
      );

  @override
  Future<StartResult> start(String handle,
          {String? deviceId, String? locale, String? bearer}) async =>
      StartResult(
        sessionId: 'sess-1',
        greeting: '',
        quickReplies: const [],
        entryPoint: await resolve(handle),
      );

  @override
  Future<List<InboxMessage>> inbox(String handle, {String? bearer, bool peek = false}) async =>
      const [];

  @override
  Stream<SseEvent> sendMessage(String sessionId, String text,
      {String? bearer,
      List<String> attachmentIds = const [],
      Map<String, Object?>? selection}) async* {
    sent.add(_Sent(text, selection));
    for (final e in nextTurn) {
      yield e;
    }
  }
}

class _FakeCreds extends CredentialStore {
  @override
  Future<Credentials> load(SharedPreferences prefs) async => const Credentials();
  @override
  Future<void> savePass(String value) async {}
  @override
  Future<void> saveSecret(String value) async {}
  @override
  Future<void> clear() async {}
  @override
  Future<void> clearPassOnly() async {}
}

SseEvent _token(String text) => SseEvent('token', {'text': text});

SseEvent _chips(Map<String, Object?> payload) =>
    SseEvent('ui_hint', {'kind': 'quick_replies', 'payload': payload});

/// Assistant-channels design §2.2: the canonical `options` shape renders as
/// chips, a tap sends the option's text with the `option` selection, and the
/// legacy `actions` / `items` shapes keep working.
void main() {
  late _FakeApi api;
  late ProviderContainer container;
  late ChatController controller;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    api = _FakeApi();
    container = ProviderContainer(overrides: [
      sharedPrefsProvider.overrideWithValue(prefs),
      initialCredentialsProvider.overrideWithValue(const Credentials()),
      credentialStoreProvider.overrideWithValue(_FakeCreds()),
      chatApiProvider.overrideWithValue(api),
    ]);
    controller = container.read(chatControllerProvider('e_ops').notifier);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(container.read(chatControllerProvider('e_ops')).sessionId, 'sess-1');
  });

  tearDown(() => container.dispose());

  ChatState state() => container.read(chatControllerProvider('e_ops'));

  group('QuickReply parsing', () {
    test('an options entry keeps id, send and style', () {
      final q = QuickReply.fromJson(
          {'id': 'undo', 'label': 'Anular', 'send': 'Anular el ticket 4411', 'style': 'danger'});
      expect(q.id, 'undo');
      expect(q.label, 'Anular');
      expect(q.send, 'Anular el ticket 4411');
      expect(q.style, 'danger');
      expect(q.isOption, isTrue);
      expect(q.selection, {'kind': 'option', 'id': 'undo', 'send': 'Anular el ticket 4411'});
    });

    test('a legacy actions entry has no id and no selection', () {
      final q = QuickReply.fromJson({'label': 'En PDF', 'send': 'Mándamelo en PDF'});
      expect(q.id, isNull);
      expect(q.selection, isNull);
      expect(const QuickReply.text('Cuadre').send, 'Cuadre');
    });
  });

  group('chips on the bubble', () {
    test('the options shape wins and renders every option', () async {
      api.nextTurn = [
        _token('¿Qué quieres hacer con este ticket?'),
        _chips({
          'options': [
            {'id': 'undo', 'label': 'Anular', 'send': 'Anular el ticket 4411', 'style': 'danger'},
            {'id': 'pay', 'label': 'Pagar premio'},
          ],
          'source': 'rule',
        }),
      ];
      await controller.send('ticket 4411');
      final chips = state().messages.last.quickReplies;
      expect(chips.map((q) => q.label), ['Anular', 'Pagar premio']);
      expect(chips.map((q) => q.send), ['Anular el ticket 4411', 'Pagar premio']);
      expect(chips.map((q) => q.id), ['undo', 'pay']);
      expect(chips.first.style, 'danger');
    });

    test('legacy actions and items still render', () async {
      api.nextTurn = [
        _token('Listo.'),
        _chips({
          'items': ['En PDF'],
          'actions': [
            {'label': 'En PDF', 'send': 'Mándamelo en PDF'},
          ],
        }),
      ];
      await controller.send('cuadre');
      expect(state().messages.last.quickReplies.single.send, 'Mándamelo en PDF');
      expect(state().messages.last.quickReplies.single.id, isNull);

      api.nextTurn = [
        _token('Hola.'),
        _chips({
          'items': ['Cuadre de hoy', 'Ver tickets'],
        }),
      ];
      await controller.send('hola');
      expect(state().messages.last.quickReplies.map((q) => q.label), ['Cuadre de hoy', 'Ver tickets']);
    });
  });

  group('chip taps', () {
    test('an option chip sends its text with the option selection', () async {
      api.nextTurn = const [];
      await controller.tapQuickReply(const QuickReply(
          id: 'undo', label: 'Anular', send: 'Anular el ticket 4411', style: 'danger'));
      expect(api.sent.single.text, 'Anular el ticket 4411');
      expect(api.sent.single.selection,
          {'kind': 'option', 'id': 'undo', 'send': 'Anular el ticket 4411'});
    });

    test('a legacy chip sends its text, no selection', () async {
      api.nextTurn = const [];
      await controller.tapQuickReply(const QuickReply.text('Cuadre de hoy'));
      expect(api.sent.single.text, 'Cuadre de hoy');
      expect(api.sent.single.selection, isNull);
    });
  });
}

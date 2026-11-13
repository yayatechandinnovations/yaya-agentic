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

SseEvent _confirm(String summary,
        {String callId = 'call-1',
        List<Object>? items,
        String? confirmLabel,
        String? cancelLabel}) =>
    SseEvent('ui_hint', {
      'kind': 'confirm',
      'payload': {
        'toolId': 'op_ticket_undo',
        'callId': callId,
        'args': {'ticketNumber': '1200345678'},
        'summary': summary,
        'items': ?items,
        'confirmLabel': ?confirmLabel,
        'cancelLabel': ?cancelLabel,
      },
    });

/// WhatsApp ops §14.3 / §6.7 C1: bound action taps and confirm replies go out
/// as structured selections, and the confirm question is never shown twice.
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
    // Let the session start (resolve → start).
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(container.read(chatControllerProvider('e_ops')).sessionId, 'sess-1');
  });

  tearDown(() => container.dispose());

  ChatState state() => container.read(chatControllerProvider('e_ops'));

  group('MessageAction parsing', () {
    test('a bound tool action has an actionId and no send', () {
      final a = MessageAction.fromJson(
          {'label': 'Aprobar', 'style': 'primary', 'actionId': 'act-9f2'});
      expect(a.isBound, isTrue);
      expect(a.actionId, 'act-9f2');
      expect(a.label, 'Aprobar');
      expect(a.style, 'primary');
    });

    test('a legacy send action stays unbound', () {
      final a = MessageAction.fromJson({'label': 'Ver detalle', 'send': 'Ver el detalle'});
      expect(a.isBound, isFalse);
      expect(a.send, 'Ver el detalle');
    });

    test('an inbox message keeps both kinds side by side', () {
      final m = InboxMessage.fromJson({
        'sessionId': 's',
        'body': 'Solicitud UR-1 pendiente',
        'actions': [
          {'label': 'Aprobar', 'style': 'primary', 'actionId': 'a1'},
          {'label': 'Ver detalle', 'send': 'Ver el detalle de UR-1', 'actionId': 'a3'},
          {'label': 'Viejo', 'send': 'texto'},
        ],
      });
      expect(m.actions.map((a) => a.actionId), ['a1', 'a3', null]);
    });
  });

  group('action taps', () {
    test('a bound action sends its label + the action selection', () async {
      await controller.tapMessageAction(
          const MessageAction(label: 'Aprobar', send: 'Aprobar', actionId: 'act-1'));
      expect(api.sent.single.text, 'Aprobar');
      expect(api.sent.single.selection, {'kind': 'action', 'actionId': 'act-1'});
    });

    test('a legacy action sends its send text, no selection', () async {
      await controller.tapMessageAction(
          const MessageAction(label: 'Ver detalle', send: 'Ver el detalle de UR-1'));
      expect(api.sent.single.text, 'Ver el detalle de UR-1');
      expect(api.sent.single.selection, isNull);
    });
  });

  group('one confirmation (C1)', () {
    test('the confirm summary token is dropped from the bubble (token first)', () async {
      api.nextTurn = [
        _token('Voy a deshacer el boleto 1200345678. '),
        _token('¿Deshacer el boleto 1200345678?'),
        _confirm('¿Deshacer el boleto 1200345678?'),
      ];
      await controller.send('anula el boleto 1200345678');
      expect(state().messages.last.text, 'Voy a deshacer el boleto 1200345678.');
      expect(state().pendingConfirm?.callId, 'call-1');
    });

    test('hint first + an old engine suffix: still shown only on the card', () async {
      api.nextTurn = [
        _confirm('¿Deshacer el boleto 1200345678?'),
        _token('¿Deshacer el boleto 1200345678? (reply yes or no)'),
      ];
      await controller.send('anula el boleto 1200345678');
      expect(state().messages.last.text, isEmpty);
      expect(state().pendingConfirm, isNotNull);
    });

    test('Confirmar carries the pending callId', () async {
      api.nextTurn = [_confirm('¿Deshacer?', callId: 'call-42')];
      await controller.send('anula');
      api.nextTurn = const [];
      await controller.confirmPending();
      expect(api.sent.last.text, 'Confirmar');
      expect(api.sent.last.selection, {'kind': 'confirm', 'callId': 'call-42'});
      expect(state().pendingConfirm, isNull);
    });

    test('Cancelar carries the pending callId', () async {
      api.nextTurn = [_confirm('¿Deshacer?', callId: 'call-43')];
      await controller.send('anula');
      api.nextTurn = const [];
      await controller.cancelPending();
      expect(api.sent.last.text, 'Cancelar');
      expect(api.sent.last.selection, {'kind': 'cancel', 'callId': 'call-43'});
    });

    test('the frame\'s own labels are what the tap sends', () async {
      api.nextTurn = [
        _confirm('Undo ticket 1200345678?',
            callId: 'call-8', confirmLabel: 'Confirm', cancelLabel: 'Cancel'),
      ];
      await controller.send('undo it');
      api.nextTurn = const [];
      await controller.cancelPending();
      expect(api.sent.last.text, 'Cancel');
      expect(api.sent.last.selection, {'kind': 'cancel', 'callId': 'call-8'});
    });

    test('multi-item approval keeps confirm_items with the checked ids', () async {
      api.nextTurn = [
        _confirm('¿Aprobar 2 solicitudes?', callId: 'call-7', confirmLabel: 'SÍ (2)', items: [
          {'id': 'UR-1', 'label': 'UR-1'},
          {'id': 'UR-2', 'label': 'UR-2'},
        ]),
      ];
      await controller.send('aprueba todas');
      api.nextTurn = const [];
      await controller.confirmPendingItems(['UR-2']);
      expect(api.sent.last.text, 'SÍ (2)');
      expect(api.sent.last.selection, {
        'kind': 'confirm_items',
        'callId': 'call-7',
        'itemIds': ['UR-2'],
      });
    });
  });

  test('stripConfirmSummary leaves text without the summary untouched', () {
    expect(stripConfirmSummary('hola', '¿Seguro?'), 'hola');
    expect(stripConfirmSummary('hola', null), 'hola');
    expect(stripConfirmSummary('Listo. ¿Seguro?', '¿Seguro?'), 'Listo.');
  });
}

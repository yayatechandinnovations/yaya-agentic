/// Web implementation of [EmbeddedSignupLauncher]: injects the Facebook JS
/// SDK (only when this page needs it), initializes it, opens the Embedded
/// Signup popup via `FB.login`, and forwards origin-validated
/// `window.postMessage` events to the wizard.
///
/// `dart:html` for DOM access (the repo's established style — see
/// `web_file_picker.dart`) plus `dart:js_interop` for the FB SDK calls
/// (`dart:js_util` no longer exists in current Dart SDKs).
library;

import 'dart:async';
import 'dart:html' as html;
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'embedded_signup_events.dart';
import 'signup_launcher.dart';

EmbeddedSignupLauncher create() => _WebEmbeddedSignupLauncher();

@JS('fbAsyncInit')
external set _fbAsyncInit(JSFunction f);

@JS('FB')
external JSObject? get _fb;

class _WebEmbeddedSignupLauncher implements EmbeddedSignupLauncher {
  static const _scriptId = 'facebook-jssdk';
  static const _sdkSrc = 'https://connect.facebook.net/en_US/sdk.js';
  static const _graphApiVersion = 'v23.0';

  final _events = StreamController<EmbeddedSignupEvent>.broadcast();
  StreamSubscription<html.MessageEvent>? _messageSub;
  static Future<void>? _sdkReady;

  @override
  Future<void> load({required String appId}) {
    _messageSub ??= html.window.onMessage.listen((event) {
      Object? data = event.data;
      if (data is! String && data is! Map) {
        try {
          data = (data as JSAny?).dartify();
        } catch (_) {
          return;
        }
      }
      final parsed = parseEmbeddedSignupMessage(origin: event.origin, data: data);
      if (parsed is! SignupIgnored) _events.add(parsed);
    });

    return _sdkReady ??= _injectAndInit(appId);
  }

  Future<void> _injectAndInit(String appId) {
    final completer = Completer<void>();
    _fbAsyncInit = (() {
      final fb = _fb;
      if (fb != null) {
        fb.callMethod(
            'init'.toJS,
            {
              'appId': appId,
              'autoLogAppEvents': true,
              'xfbml': false,
              'version': _graphApiVersion,
            }.jsify());
      }
      if (!completer.isCompleted) completer.complete();
    }).toJS;

    if (html.document.getElementById(_scriptId) == null) {
      final script = html.ScriptElement()
        ..id = _scriptId
        ..src = _sdkSrc
        ..async = true
        ..defer = true
        ..crossOrigin = 'anonymous';
      script.onError.listen((_) {
        if (!completer.isCompleted) {
          completer
              .completeError(StateError('Could not load the Facebook SDK.'));
        }
      });
      html.document.head!.append(script);
    }
    return completer.future.timeout(const Duration(seconds: 20),
        onTimeout: () {
      throw TimeoutException('Facebook SDK did not initialize in time.');
    });
  }

  @override
  void launch({required String configurationId, String? partnerSolutionId}) {
    final fb = _fb;
    if (fb == null) {
      _events.add(const SignupError(
          message: 'The Facebook SDK is not loaded yet — try again.'));
      return;
    }
    final extras = <String, Object?>{
      'sessionInfoVersion': '3',
      if (partnerSolutionId != null && partnerSolutionId.isNotEmpty)
        'setup': {'solutionID': partnerSolutionId},
    };
    final callback = ((JSAny? response) {
      // The definitive signup result arrives via postMessage (handled in
      // load()). A login callback without an auth response means the
      // operator closed the dialog before authorizing — the wizard dedups
      // a late CANCEL after a FINISH.
      final auth = response is JSObject
          ? response.getProperty('authResponse'.toJS)
          : null;
      if (auth == null || auth.isUndefinedOrNull) {
        _events.add(const SignupCancel(currentStep: 'login'));
      }
    }).toJS;
    fb.callMethod(
        'login'.toJS,
        callback,
        {
          'config_id': configurationId,
          // A retry after the operator unticked a permission must ask again.
          'auth_type': 'rerequest',
          'response_type': 'code',
          'override_default_response_type': true,
          'extras': extras,
        }.jsify());
  }

  @override
  Stream<EmbeddedSignupEvent> get events => _events.stream;

  @override
  void dispose() {
    _messageSub?.cancel();
    _messageSub = null;
    _events.close();
  }
}

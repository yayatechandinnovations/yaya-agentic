import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/router.dart';
import '../firebase_options.dart';
import 'providers.dart';

/// Content-free push wake (system-messaging §11; chat-mobile-publishing-and-push
/// §B). Every path here degrades to a no-op when Firebase isn't configured, so
/// dev/web builds and `flutter test` run with no Firebase project.
///
/// Payload contract (set by the backend FcmPushSender): a generic title/body
/// plus data `{type: outreach_wake, handle, entryPointId, sender}`. The real
/// message never rides the push — the client drains `/inbox` for `handle`.

/// FCM tokens are FCM tokens on every platform firebase_messaging supports
/// (Android, iOS-over-APNs, and web), so one platform tag covers all of them.
const String _fcmPlatform = 'FCM';

const String _androidChannelId = 'messages';
const String _androidChannelName = 'Messages';

/// A foreground wake ping the visible chat listens to so it re-drains its inbox
/// without waiting for the next open. `seq` forces a state change on repeats.
class PushPing {
  final String handle;
  final int seq;
  const PushPing(this.handle, this.seq);
}

final pushInboxPingProvider = StateProvider<PushPing?>((ref) => null);

/// Set by [bootstrapPush] in main(): true only when Firebase actually
/// initialized. Overridden there; the default keeps push off (e.g. in tests).
final pushAvailableProvider = Provider<bool>((ref) => false);

final pushServiceProvider = Provider<PushService>(
  (ref) => PushService(ref, available: ref.watch(pushAvailableProvider)),
);

/// Background/terminated messages carry a `notification` block, so the OS shows
/// the tray entry itself — nothing to do here but exist and keep Firebase alive
/// for the isolate. Must be a top-level, vm-entry-point function.
@pragma('vm:entry-point')
Future<void> firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  // Intentionally empty: content-free wake, tray handled by the OS, inbox
  // drained on open.
}

/// Initialize Firebase once, before runApp. Returns whether push is available;
/// pass the result into [pushAvailableProvider] via a ProviderScope override.
Future<bool> bootstrapPush() async {
  if (!DefaultFirebaseOptions.configured) return false;
  try {
    await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
    FirebaseMessaging.onBackgroundMessage(firebaseMessagingBackgroundHandler);
    return true;
  } catch (_) {
    // Never let a push-init failure white-screen the app — it just falls back
    // to inbox polling.
    return false;
  }
}

class PushService {
  PushService(this._ref, {required bool available}) : _available = available;

  final Ref _ref;
  final bool _available;

  FlutterLocalNotificationsPlugin? _localNotifications;
  String? _lastToken;
  bool _started = false;

  /// Wire the message streams once. Safe to call repeatedly.
  Future<void> start() async {
    if (!_available || _started) return;
    _started = true;

    // iOS shows foreground notifications itself when asked; Android needs a
    // local notification (below).
    await FirebaseMessaging.instance.setForegroundNotificationPresentationOptions(
      alert: true,
      badge: true,
      sound: true,
    );

    FirebaseMessaging.onMessage.listen(_onForegroundMessage);
    FirebaseMessaging.onMessageOpenedApp.listen(_onMessageOpened);
    FirebaseMessaging.instance.onTokenRefresh.listen((_) => unawaited(syncRegistration()));

    // App launched from a terminated state by tapping a notification.
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) _onMessageOpened(initial);
  }

  /// Ensure this device is registered for the signed-in recipient. Idempotent —
  /// safe to call on verify, on tile changes, and on token refresh.
  Future<void> syncRegistration() async {
    if (!_available) return;
    if (!_ref.read(verificationProvider).verified) return;
    final handle = _anyHandle();
    if (handle == null) return; // no tiles yet → nothing to be notified about

    final settings = await FirebaseMessaging.instance.requestPermission();
    if (settings.authorizationStatus == AuthorizationStatus.denied) return;

    final token = await FirebaseMessaging.instance.getToken();
    if (token == null || token.isEmpty) return;

    await _ref.read(chatApiProvider).registerPushToken(handle, token, platform: _fcmPlatform);
    _lastToken = token;
    await _ensureLocalNotifications();
  }

  /// Drop this device's token on sign-out/delete so it stops receiving wakes.
  /// Best-effort; must be called while the device pass is still valid (i.e.
  /// before the credentials are cleared).
  Future<void> unregister() async {
    if (!_available) return;
    final token = _lastToken ?? await _safeToken();
    if (token == null || token.isEmpty) return;
    final handle = _anyHandle();
    if (handle == null) return;
    await _ref.read(chatApiProvider).unregisterPushToken(handle, token);
    _lastToken = null;
  }

  Future<String?> _safeToken() async {
    try {
      return await FirebaseMessaging.instance.getToken();
    } catch (_) {
      return null;
    }
  }

  /// Any added tile's handle works: registration is device+recipient level on
  /// the backend, and the handle is just the authenticated path to the endpoint.
  String? _anyHandle() {
    final tiles = _ref.read(tilesProvider);
    return tiles.isEmpty ? null : tiles.first.handle;
  }

  void _onForegroundMessage(RemoteMessage message) {
    final handle = message.data['handle'] as String?;
    if (handle != null && handle.isNotEmpty) {
      // Nudge the visible chat (if any) to re-drain its inbox.
      final prev = _ref.read(pushInboxPingProvider);
      _ref.read(pushInboxPingProvider.notifier).state =
          PushPing(handle, (prev?.seq ?? 0) + 1);
    }
    // Android won't surface a foreground notification on its own.
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      unawaited(_showLocal(message));
    }
  }

  void _onMessageOpened(RemoteMessage message) {
    final handle = message.data['handle'] as String?;
    if (handle != null && handle.isNotEmpty) {
      // Opening the chat drains its inbox (ChatController._drainInbox on start).
      appRouter.go('/chat/$handle');
    }
  }

  Future<void> _ensureLocalNotifications() async {
    if (kIsWeb || _localNotifications != null) return;
    final plugin = FlutterLocalNotificationsPlugin();
    const init = InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      iOS: DarwinInitializationSettings(),
    );
    await plugin.initialize(init);
    _localNotifications = plugin;
  }

  Future<void> _showLocal(RemoteMessage message) async {
    await _ensureLocalNotifications();
    final plugin = _localNotifications;
    final n = message.notification;
    if (plugin == null || n == null) return;
    const details = NotificationDetails(
      android: AndroidNotificationDetails(
        _androidChannelId,
        _androidChannelName,
        importance: Importance.high,
        priority: Priority.high,
      ),
    );
    await plugin.show(n.hashCode, n.title, n.body, details,
        payload: message.data['handle'] as String?);
  }
}

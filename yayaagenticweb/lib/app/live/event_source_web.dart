// ignore_for_file: deprecated_member_use, avoid_web_libraries_in_flutter
import 'dart:async';
import 'dart:html' as html;

import 'event_source.dart';

FeedConnector createConnector() => _BrowserFeed();

PageSignals createPageSignals() => _BrowserSignals();

/// Native EventSource rather than a dio stream: it carries the session cookie
/// (withCredentials), reconnects on its own with Last-Event-ID, and doesn't
/// buffer the whole response the way a long-lived XHR does.
class _BrowserFeed implements FeedConnector {
  @override
  Stream<FeedMessage> connect(Uri url) {
    late html.EventSource source;
    final subs = <StreamSubscription<Object?>>[];
    final controller = StreamController<FeedMessage>(onCancel: () {
      for (final s in subs) {
        s.cancel();
      }
      source.close();
    });
    source = html.EventSource(url.toString(), withCredentials: true);
    subs.add(source.onOpen.listen((_) => controller.add(const FeedOpened())));
    subs.add(source.onError.listen((_) {
      if (source.readyState == html.EventSource.CLOSED) {
        controller.add(const FeedClosed());
      } else {
        controller.add(const FeedInterrupted());
      }
    }));
    for (final type in const ['hello', 'resource-changed', 'reset']) {
      subs.add(source.on[type].listen((e) {
        if (e is! html.MessageEvent) return;
        final id = e.lastEventId;
        controller.add(FeedEvent(type, '${e.data}', lastEventId: id.isEmpty ? null : id));
      }));
    }
    return controller.stream;
  }
}

class _BrowserSignals implements PageSignals {
  @override
  bool get isVisible => html.document.visibilityState != 'hidden';

  @override
  bool get isOnline => html.window.navigator.onLine ?? true;

  @override
  Stream<bool> get visibilityChanges => html.document.onVisibilityChange.map((_) => isVisible);

  @override
  Stream<bool> get onlineChanges => StreamGroupLite.merge([
        html.window.onOnline.map((_) => true),
        html.window.onOffline.map((_) => false),
      ]);
}

abstract final class StreamGroupLite {
  static Stream<T> merge<T>(List<Stream<T>> streams) {
    final subs = <StreamSubscription<T>>[];
    late StreamController<T> c;
    c = StreamController<T>.broadcast(
      onListen: () {
        for (final s in streams) {
          subs.add(s.listen(c.add));
        }
      },
      onCancel: () {
        for (final s in subs) {
          s.cancel();
        }
        subs.clear();
      },
    );
    return c.stream;
  }
}

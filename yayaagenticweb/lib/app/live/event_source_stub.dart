import 'dart:async';

import 'event_source.dart';

FeedConnector createConnector() => _NoFeed();

PageSignals createPageSignals() => _AlwaysVisible();

class _NoFeed implements FeedConnector {
  @override
  Stream<FeedMessage> connect(Uri url) => const Stream.empty();
}

class _AlwaysVisible implements PageSignals {
  @override
  bool get isVisible => true;
  @override
  bool get isOnline => true;
  @override
  Stream<bool> get visibilityChanges => const Stream.empty();
  @override
  Stream<bool> get onlineChanges => const Stream.empty();
}

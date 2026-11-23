import 'event_source_stub.dart' if (dart.library.html) 'event_source_web.dart' as impl;

/// What the feed connection reports, decoupled from the browser API so the
/// controller is testable off-browser.
sealed class FeedMessage {
  const FeedMessage();
}

class FeedOpened extends FeedMessage {
  const FeedOpened();
}

class FeedEvent extends FeedMessage {
  const FeedEvent(this.type, this.data, {this.lastEventId});
  final String type;
  final String data;
  final String? lastEventId;
}

/// The browser is retrying on its own (network blip).
class FeedInterrupted extends FeedMessage {
  const FeedInterrupted();
}

/// The connection is gone for good (401, 404, server refused); the caller
/// decides whether and when to try again.
class FeedClosed extends FeedMessage {
  const FeedClosed();
}

abstract class FeedConnector {
  /// Opens [url] with credentials. Cancelling the subscription closes it.
  Stream<FeedMessage> connect(Uri url);
}

/// Page visibility + network signals the refresh policy reacts to.
abstract class PageSignals {
  bool get isVisible;
  bool get isOnline;
  Stream<bool> get visibilityChanges;
  Stream<bool> get onlineChanges;
}

FeedConnector defaultFeedConnector() => impl.createConnector();

PageSignals defaultPageSignals() => impl.createPageSignals();

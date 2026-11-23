import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'admin_feed.dart';
import 'resource_kind.dart';

/// Call first thing in a list provider's body. The provider refetches —
/// keeping its previous value on screen — whenever the change feed reports
/// one of [kinds], or a global refresh is due (refocus, reconnect, fallback).
///
/// Invalidation is lazy for providers nobody is watching, so a change to a
/// screen that isn't open costs nothing until the operator opens it.
void liveResource(Ref ref, AdminResourceKind kind, [List<AdminResourceKind> also = const []]) {
  final invalidate = _deferredInvalidate(ref);
  for (final k in [kind, ...also]) {
    ref.listen<int>(adminChangeSignalProvider(k), (_, _) => invalidate());
  }
  ref.listen<int>(refreshEpochProvider, (_, _) => invalidate());
}

/// Listeners can fire while Riverpod is still walking this provider's
/// dependencies; invalidating synchronously there is a concurrent
/// modification. Defer to a microtask, and never touch a disposed ref.
void Function() _deferredInvalidate(Ref ref) {
  var disposed = false;
  var scheduled = false;
  ref.onDispose(() => disposed = true);
  return () {
    if (scheduled) return;
    scheduled = true;
    scheduleMicrotask(() {
      scheduled = false;
      if (!disposed) ref.invalidateSelf();
    });
  };
}

/// For resources with transient states the feed may not see (a Twilio
/// callback, an external worker): refetch every [every] while [busy], paused
/// while the tab is hidden. Call after the fetch resolves.
void pollWhile(Ref ref, bool busy, {Duration every = const Duration(seconds: 3)}) {
  if (!busy) return;
  final signals = ref.read(pageSignalsProvider);
  Timer? timer;
  void arm() {
    timer = Timer(every, () {
      if (signals.isVisible) {
        ref.invalidateSelf();
      } else {
        arm();
      }
    });
  }

  arm();
  ref.onDispose(() => timer?.cancel());
}

/// The latest change the feed reported for one record, if any.
final recentChangeProvider = Provider.family<AdminChange?, ({AdminResourceKind kind, String id})>((ref, key) {
  final recent = ref.watch(adminRecentChangesProvider);
  for (final c in recent) {
    if (c.kind == key.kind && c.id == key.id) return c;
  }
  return null;
});

/// Ids of [kind] that changed within [window] — rows to highlight.
Set<String> freshIds(List<AdminChange> recent, AdminResourceKind kind, DateTime now,
    {Duration window = const Duration(seconds: 6)}) {
  return {
    for (final c in recent)
      if (c.kind == kind && c.id != null && now.difference(c.receivedAt) < window) c.id!,
  };
}

/// For lists with no change-feed kind (audit pages, memberships): refetch on
/// the global triggers only — refocus, reconnect, the 30 s fallback.
void liveRefresh(Ref ref) {
  final invalidate = _deferredInvalidate(ref);
  ref.listen<int>(refreshEpochProvider, (_, _) => invalidate());
}

/// [freshIds] that clears itself once the highlight window has passed.
final freshIdsProvider = Provider.autoDispose.family<Set<String>, AdminResourceKind>((ref, kind) {
  final ids = freshIds(ref.watch(adminRecentChangesProvider), kind, DateTime.now());
  if (ids.isNotEmpty) {
    final t = Timer(const Duration(seconds: 6), ref.invalidateSelf);
    ref.onDispose(t.cancel);
  }
  return ids;
});

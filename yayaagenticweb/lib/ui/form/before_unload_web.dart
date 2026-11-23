// ignore_for_file: deprecated_member_use, avoid_web_libraries_in_flutter
import 'dart:async';
import 'dart:html' as html;

StreamSubscription<html.Event>? _sub;

/// The browser's own "Leave site?" prompt while anything is unsaved.
void setBeforeUnloadPrompt(bool enabled) {
  if (enabled && _sub == null) {
    _sub = html.window.onBeforeUnload.listen((e) {
      if (e is html.BeforeUnloadEvent) {
        e.preventDefault();
        e.returnValue = '';
      }
    });
  } else if (!enabled && _sub != null) {
    _sub!.cancel();
    _sub = null;
  }
}

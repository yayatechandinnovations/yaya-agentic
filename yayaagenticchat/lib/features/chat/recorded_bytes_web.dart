import 'dart:js_interop';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

/// Browser-native fetch: the only blob:-URL read that works across Chrome,
/// Firefox and Safari (XHR on blob: is blocked in Firefox).
Future<Uint8List> readRecordedBytes(String pathOrUrl) async {
  final response = await web.window.fetch(pathOrUrl.toJS).toDart;
  final buffer = await response.arrayBuffer().toDart;
  return buffer.toDart.asUint8List();
}

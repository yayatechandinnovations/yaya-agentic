import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

import 'recorded_bytes_io.dart' if (dart.library.js_interop) 'recorded_bytes_stub.dart';

/// Read the bytes a finished recording left behind. On web the recorder hands
/// back a `blob:` URL (fetched via XHR); on mobile/desktop it's a file path.
Future<Uint8List> readRecordedBytes(String pathOrUrl) async {
  if (kIsWeb) {
    final r = await Dio().get<List<int>>(pathOrUrl,
        options: Options(responseType: ResponseType.bytes));
    return Uint8List.fromList(r.data ?? const []);
  }
  return readFileBytes(pathOrUrl);
}

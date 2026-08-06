import 'dart:typed_data';

/// Web never reaches this branch — recordings arrive as blob: URLs.
Future<Uint8List> readFileBytes(String path) async =>
    throw UnsupportedError('file reads are not available on this platform');

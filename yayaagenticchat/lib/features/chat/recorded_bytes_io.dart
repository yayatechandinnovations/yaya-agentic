import 'dart:io';
import 'dart:typed_data';

Future<Uint8List> readRecordedBytes(String pathOrUrl) =>
    File(pathOrUrl).readAsBytes();

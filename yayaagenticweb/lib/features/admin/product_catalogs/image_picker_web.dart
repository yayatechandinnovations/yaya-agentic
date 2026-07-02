import 'dart:async';
import 'dart:html' as html;
import 'dart:typed_data';

/// A picked image: raw bytes + filename + MIME type (used for the multipart
/// Content-Type the backend validates as image/*).
class PickedImage {
  PickedImage(this.bytes, this.name, this.contentType);
  final Uint8List bytes;
  final String name;
  final String contentType;
}

/// Pick one image via the browser's native file input. The admin app is
/// web-only, so this uses dart:html directly (same approach as the knowledge
/// file picker). Returns null if dismissed.
Future<PickedImage?> pickImageFromBrowser() async {
  final input = html.FileUploadInputElement()
    ..accept = 'image/*'
    ..multiple = false;
  input.click();

  await input.onChange.first;
  final files = input.files;
  if (files == null || files.isEmpty) return null;

  final file = files.first;
  final reader = html.FileReader();
  reader.readAsArrayBuffer(file);
  await reader.onLoadEnd.first;

  final result = reader.result;
  Uint8List? bytes;
  if (result is Uint8List) {
    bytes = result;
  } else if (result is ByteBuffer) {
    bytes = result.asUint8List();
  }
  if (bytes == null) return null;
  return PickedImage(bytes, file.name,
      file.type.isEmpty ? 'image/png' : file.type);
}

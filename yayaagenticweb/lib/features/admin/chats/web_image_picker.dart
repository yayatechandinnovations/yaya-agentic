import 'dart:async';
import 'dart:html' as html;
import 'dart:typed_data';

/// A picked image: raw bytes + its MIME content type.
class PickedImage {
  PickedImage(this.bytes, this.contentType);
  final Uint8List bytes;
  final String contentType;
}

const _extMime = {
  'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg',
  'webp': 'image/webp', 'gif': 'image/gif',
};

/// Pick an image via the browser's native file input. Used instead of the
/// `file_picker` plugin, which throws a LateInitializationError on web. The
/// admin app is web-only, so `dart:html` (already used by api_client) is fine.
///
/// Returns null if the user dismisses without choosing (or the read fails).
Future<PickedImage?> pickImageFromBrowser() async {
  final input = html.FileUploadInputElement()
    ..accept = 'image/png,image/jpeg,image/webp,image/gif'
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
  final Uint8List bytes;
  if (result is Uint8List) {
    bytes = result;
  } else if (result is ByteBuffer) {
    bytes = result.asUint8List();
  } else {
    return null;
  }

  var contentType = file.type;
  if (contentType.isEmpty) {
    final ext = file.name.contains('.') ? file.name.split('.').last.toLowerCase() : '';
    contentType = _extMime[ext] ?? '';
  }
  return PickedImage(bytes, contentType);
}

import 'dart:async';
import 'dart:html' as html;
import 'dart:typed_data';

/// A picked document: raw bytes + original filename + best-effort MIME type.
/// The backend re-sniffs the content type from the bytes, so [contentType]
/// here is only advisory.
class PickedFile {
  PickedFile(this.bytes, this.name, this.contentType);
  final Uint8List bytes;
  final String name;
  final String contentType;
}

/// Accept filter for the native picker — the document types the backend
/// allow-lists (knowledge-file-upload-design.md §11).
const _accept =
    '.pdf,.txt,.md,.markdown,.html,.htm,.rtf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,'
    'application/pdf,text/plain,text/markdown,text/html';

/// Pick one or more documents via the browser's native file input. Uses
/// `dart:html` directly (not the `file_picker` plugin, which misbehaves on
/// web) — the admin app is web-only. Returns an empty list if dismissed.
Future<List<PickedFile>> pickFilesFromBrowser() async {
  final input = html.FileUploadInputElement()
    ..accept = _accept
    ..multiple = true;
  input.click();

  await input.onChange.first;
  final files = input.files;
  if (files == null || files.isEmpty) return const [];

  final picked = <PickedFile>[];
  for (final file in files) {
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
    if (bytes == null) continue;
    picked.add(PickedFile(bytes, file.name, file.type));
  }
  return picked;
}

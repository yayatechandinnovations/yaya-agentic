import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'image_picker.dart';

/// Web implementation — an invisible `<input type=file accept=image/*>`.
/// Isolated here so nothing else (and no test) pulls in web-only libraries.
class WebImagePicker implements ImagePicker {
  @override
  Future<PickedImage?> pickImage() {
    final completer = Completer<PickedImage?>();
    final input = web.document.createElement('input') as web.HTMLInputElement;
    input.type = 'file';
    input.accept = 'image/*';

    input.addEventListener(
      'change',
      ((web.Event _) {
        final files = input.files;
        if (files == null || files.length == 0) {
          completer.complete(null);
          return;
        }
        final file = files.item(0)!;
        final reader = web.FileReader();
        reader.addEventListener(
          'load',
          ((web.Event _) {
            final buffer = (reader.result as JSArrayBuffer).toDart;
            completer.complete(PickedImage(
              bytes: buffer.asUint8List(),
              filename: file.name.isEmpty ? 'image' : file.name,
              contentType: file.type.isEmpty ? 'image/png' : file.type,
            ));
          }).toJS,
        );
        reader.readAsArrayBuffer(file);
      }).toJS,
    );

    input.click();
    return completer.future;
  }
}

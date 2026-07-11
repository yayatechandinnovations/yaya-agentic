import 'dart:typed_data';

/// A locally picked image, ready to upload.
class PickedImage {
  const PickedImage({
    required this.bytes,
    required this.filename,
    required this.contentType,
  });

  final Uint8List bytes;
  final String filename;
  final String contentType;
}

/// Abstracts the browser file dialog so pages and tests never touch web-only
/// libraries (mirrors the OAuthLauncher seam).
abstract class ImagePicker {
  /// Resolves null when the user cancels the dialog.
  Future<PickedImage?> pickImage();
}

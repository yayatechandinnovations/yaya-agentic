/// Platform-split document preview + download (owner-ai-experience v3 §D1).
///
/// The web implementation hosts a native `<iframe>` (browsers render PDF inline)
/// and downloads via an `<a download>` anchor; the native implementation renders
/// with `pdfx` from fetched bytes and hands the file to the OS. The conditional
/// export keeps `package:web`/`dart:ui_web` off native builds and `pdfx` off the
/// web bundle.
library;

export 'document_preview_stub.dart'
    if (dart.library.js_interop) 'document_preview_web.dart'
    if (dart.library.io) 'document_preview_native.dart';

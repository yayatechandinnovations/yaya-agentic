/// Platform-split document download (owner-ai-experience v3 §D1). Both platforms
/// now PREVIEW via the shared `pdfx`-based [PdfPreviewView] (rendered into the
/// Flutter tree — the old web `<iframe>` composited blank). This split only
/// carries the platform-specific `downloadDocument`/`openExternally`: web uses
/// an `<a download>` anchor + `window.open`, native hands the URL to the OS.
library;

export 'document_preview_stub.dart'
    if (dart.library.js_interop) 'document_preview_web.dart'
    if (dart.library.io) 'document_preview_native.dart';

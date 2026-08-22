import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

import '../../config.dart';
import '../../models/chat_message.dart';
import 'pdf_preview_view.dart';

/// Preview a PDF inline by rendering it into the Flutter tree with `pdfx`
/// (pdf.js) — NOT an `<iframe>`. The iframe/HtmlElementView path rendered blank
/// on web (the browser fetched the bytes fine — download worked — but the
/// platform view never composited the PDF), so we decode + paint it ourselves.
Widget buildDocumentPreview(ChatAttachment attachment) => PdfPreviewView(
      attachment: attachment,
      onOpenExternal: () => openExternally(attachment),
    );

/// Real browser download that stays in-app: a synthetic `<a download>` click on
/// the signed URL.
Future<void> downloadDocument(ChatAttachment attachment) async {
  final url = AppConfig.resolveUrl(attachment.url);
  final anchor = web.HTMLAnchorElement()
    ..href = url
    ..download = attachment.filename ?? 'document'
    ..target = '_blank'
    ..style.display = 'none';
  web.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
}

/// Fallback when inline rendering fails: open the signed URL in a new browser
/// tab (the browser's native PDF viewer) — stays in the browser, never launches
/// an external OS app.
void openExternally(ChatAttachment attachment) {
  web.window.open(AppConfig.resolveUrl(attachment.url), '_blank');
}

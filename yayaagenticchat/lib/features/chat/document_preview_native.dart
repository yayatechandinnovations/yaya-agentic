import 'package:flutter/widgets.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../config.dart';
import '../../models/chat_message.dart';
import 'pdf_preview_view.dart';

/// Preview a PDF inline with `pdfx` from the fetched bytes (shared with web).
Widget buildDocumentPreview(ChatAttachment attachment) => PdfPreviewView(
      attachment: attachment,
      onOpenExternal: () => openExternally(attachment),
    );

/// Native "download": open the signed URL in the OS handler so the user can
/// save/share it. Kept simple — native is not the owner's primary surface.
Future<void> downloadDocument(ChatAttachment attachment) async {
  await openExternally(attachment);
}

/// Fallback / open: hand the signed URL to the OS PDF handler.
Future<void> openExternally(ChatAttachment attachment) async {
  final uri = Uri.parse(AppConfig.resolveUrl(attachment.url));
  if (await canLaunchUrl(uri)) {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }
}

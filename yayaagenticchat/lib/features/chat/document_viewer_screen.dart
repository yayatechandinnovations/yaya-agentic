import 'package:flutter/material.dart';

import '../../config.dart';
import '../../l10n/app_localizations.dart';
import '../../models/chat_message.dart';
import 'document_preview.dart';

/// In-app document viewer (owner-ai-experience v3 §D1). Reached via `/doc` with
/// the [ChatAttachment] passed as go_router `extra`. Previews the document
/// inside the app shell (web: inline `<iframe>`; native: `pdfx`) and offers a
/// Download action — so opening a ticket/PDF no longer kicks the owner out of
/// the app.
class DocumentViewerScreen extends StatelessWidget {
  const DocumentViewerScreen({super.key, required this.attachment});

  final ChatAttachment attachment;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final title = attachment.filename ?? t.documentTitle;
    return Scaffold(
      appBar: AppBar(
        title: Text(title, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            icon: const Icon(Icons.download_outlined),
            tooltip: t.download,
            onPressed: () => downloadDocument(attachment),
          ),
        ],
      ),
      body: attachment.isImage
          ? InteractiveViewer(
              child: Center(
                child: Image.network(
                  AppConfig.resolveUrl(attachment.url),
                  errorBuilder: (ctx, err, stack) => Icon(
                    Icons.broken_image_outlined,
                    size: 48,
                    color: Theme.of(ctx).colorScheme.outline,
                  ),
                ),
              ),
            )
          : buildDocumentPreview(attachment),
    );
  }
}

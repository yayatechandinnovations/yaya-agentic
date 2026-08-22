import 'package:flutter/material.dart';

import '../../models/chat_message.dart';

/// Fallback used only when neither `dart:html`/`js_interop` nor `dart:io` is
/// available (no real Flutter target). Keeps the interface compiling.
Widget buildDocumentPreview(ChatAttachment attachment) => const SizedBox.shrink();

Future<void> downloadDocument(ChatAttachment attachment) async {}

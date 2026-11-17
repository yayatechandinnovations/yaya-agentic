import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../chats/chats_admin_screen.dart' show resolveLogoUrl;
import '../chats/web_image_picker.dart';
import '../shared/admin_shared.dart';

/// Optional WhatsApp profile photo for a sender. The image is uploaded to the
/// platform, which hosts it for Twilio to fetch at registration, and previewed
/// the way WhatsApp shows it — cropped to a circle.
class SenderLogoPicker extends ConsumerStatefulWidget {
  const SenderLogoPicker({super.key, required this.url, required this.onChanged});

  final String url;
  final ValueChanged<String> onChanged;

  @override
  ConsumerState<SenderLogoPicker> createState() => _SenderLogoPickerState();
}

class _SenderLogoPickerState extends ConsumerState<SenderLogoPicker> {
  bool _busy = false;

  // WhatsApp takes PNG or JPEG only; Meta's minimum is 192×192.
  static const _allowed = {'image/png', 'image/jpeg'};
  static const _maxBytes = 2 * 1024 * 1024; // keep in sync with ChatLogoService
  static const _minSide = 192;

  Future<void> _pick() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final picked = await pickImageFromBrowser();
    if (picked == null) return;
    if (!_allowed.contains(picked.contentType)) {
      if (mounted) showSnack(context, 'Pick a PNG or JPEG image.', error: true);
      return;
    }
    if (picked.bytes.length > _maxBytes) {
      if (mounted) showSnack(context, 'The photo must be under 2 MB. Try a smaller image.', error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final image = await decodeImageFromList(picked.bytes);
      final tooSmall = image.width < _minSide || image.height < _minSide;
      image.dispose();
      if (tooSmall) {
        if (mounted) {
          showSnack(context, 'The photo must be at least $_minSide×$_minSide pixels.', error: true);
        }
        return;
      }
      final api = await ref.read(adminApiProvider.future);
      final url = await api.uploadWhatsAppSenderLogo(
          tenant, picked.contentType, base64Encode(picked.bytes));
      widget.onChanged(url);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasLogo = widget.url.isNotEmpty;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CircleAvatar(
          radius: 28,
          backgroundColor: theme.colorScheme.surfaceContainerHighest,
          child: ClipOval(
            child: hasLogo
                ? Image.network(resolveLogoUrl(ref, widget.url),
                    width: 56, height: 56, fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined))
                : const Icon(Icons.storefront_outlined),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Profile photo', style: theme.textTheme.labelLarge),
              const SizedBox(height: 2),
              Text(
                'Optional. Shown as the WhatsApp profile picture. A square PNG or JPEG, '
                'at least 192×192 px (640×640 recommended), under 2 MB.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton.icon(
                    onPressed: _busy ? null : _pick,
                    icon: _busy
                        ? const SizedBox.square(
                            dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.upload, size: 18),
                    label: Text(hasLogo ? 'Replace photo' : 'Upload photo'),
                  ),
                  if (hasLogo)
                    TextButton(
                      onPressed: _busy ? null : () => widget.onChanged(''),
                      child: const Text('Remove'),
                    ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

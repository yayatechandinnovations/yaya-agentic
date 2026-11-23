import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../chats/chats_admin_screen.dart' show resolveLogoUrl;
import '../chats/web_image_picker.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/y_fields.dart';

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

  /// A rejected pick belongs next to the picker, not in a vanishing toast.
  String? _error;

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
      if (mounted) setState(() => _error = 'Pick a PNG or JPEG image.');
      return;
    }
    if (picked.bytes.length > _maxBytes) {
      if (mounted) setState(() => _error = 'The photo must be under 2 MB. Try a smaller image.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final image = await decodeImageFromList(picked.bytes);
      final tooSmall = image.width < _minSide || image.height < _minSide;
      image.dispose();
      if (tooSmall) {
        if (mounted) setState(() => _error = 'The photo must be at least $_minSide×$_minSide pixels.');
        return;
      }
      final api = await ref.read(adminApiProvider.future);
      final url = await api.uploadWhatsAppSenderLogo(
          tenant, picked.contentType, base64Encode(picked.bytes));
      widget.onChanged(url);
    } catch (e) {
      if (mounted) setState(() => _error = ApiErrorInfo.from(e).message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasLogo = widget.url.isNotEmpty;
    return YFieldShell(
      label: 'Profile photo',
      wireKey: 'businessProfile.logoUrl',
      optional: true,
      helper: 'Shown as the WhatsApp profile picture. A square PNG or JPEG, at least 192×192 px '
          '(640×640 recommended), under 2 MB.',
      error: _error,
      child: Row(
        children: [
          CircleAvatar(
            radius: 28,
            backgroundColor: theme.colorScheme.surfaceContainerHighest,
            child: ClipOval(
              child: hasLogo
                  ? Image.network(resolveLogoUrl(ref, widget.url),
                      width: 56,
                      height: 56,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => Icon(Icons.broken_image_outlined, color: theme.colorScheme.onSurfaceVariant))
                  : Icon(Icons.storefront_outlined, color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: 12),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: _busy ? null : _pick,
                icon: _busy
                    ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.upload, size: 18),
                label: Text(hasLogo ? 'Replace photo' : 'Upload photo'),
              ),
              if (hasLogo)
                TextButton(
                  onPressed: _busy
                      ? null
                      : () {
                          setState(() => _error = null);
                          widget.onChanged('');
                        },
                  child: const Text('Remove'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

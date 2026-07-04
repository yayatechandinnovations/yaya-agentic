import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// OTP-confirmed account deletion (chat-account-self-service-design.md §6.3).
/// Two phases in one dialog: explain the consequences and send an OTP, then
/// collect the code and confirm. On success the pass is revoked server-side, so
/// the app signs out locally and the shell returns to onboarding.
Future<void> showDeleteAccountDialog(BuildContext context, WidgetRef ref) {
  return showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const _DeleteAccountDialog(),
  );
}

enum _Phase { warn, code }

class _DeleteAccountDialog extends ConsumerStatefulWidget {
  const _DeleteAccountDialog();

  @override
  ConsumerState<_DeleteAccountDialog> createState() => _DeleteAccountDialogState();
}

class _DeleteAccountDialogState extends ConsumerState<_DeleteAccountDialog> {
  final _code = TextEditingController();
  _Phase _phase = _Phase.warn;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _sendCode() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(chatApiProvider).deleteStart();
      if (!mounted) return;
      setState(() {
        _phase = _Phase.code;
        _busy = false;
      });
    } on ChatApiException catch (e) {
      _fail(e);
    }
  }

  Future<void> _confirm() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(chatApiProvider).deleteConfirm(_code.text.trim());
      if (mounted) Navigator.of(context).pop();
      // Pass is now revoked; drop local state → shell rebuilds into onboarding.
      await ref.read(verificationProvider.notifier).signOut();
      messenger.showSnackBar(SnackBar(content: Text(t.accountDeleted)));
    } on ChatApiException catch (e) {
      _fail(e);
    }
  }

  void _fail(ChatApiException e) {
    if (!mounted) return;
    setState(() {
      _busy = false;
      _error = e.message;
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final isWarn = _phase == _Phase.warn;
    return AlertDialog(
      title: Text(isWarn ? t.deleteAccountTitle : t.deleteConfirmTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(isWarn ? t.deleteAccountWarning : t.deleteConfirmBlurb),
          if (!isWarn) ...[
            const SizedBox(height: 12),
            TextField(
              controller: _code,
              keyboardType: TextInputType.number,
              enabled: !_busy,
              decoration: InputDecoration(labelText: t.fieldCode, border: const OutlineInputBorder()),
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Theme.of(context).colorScheme.error),
          onPressed: _busy ? null : (isWarn ? _sendCode : _confirm),
          child: _busy
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(isWarn ? t.sendCode : t.deleteConfirmButton),
        ),
      ],
    );
  }
}

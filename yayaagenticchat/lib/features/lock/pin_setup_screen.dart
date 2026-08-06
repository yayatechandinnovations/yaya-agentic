import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// One-shot nudge after the first OTP on an account without a PIN
/// (chat-experience-design.md §5.5): set a 4–6 digit PIN so future opens skip
/// the SMS, or skip — the account keeps working with the long-lived pass.
class PinSetupScreen extends ConsumerStatefulWidget {
  const PinSetupScreen({super.key});

  @override
  ConsumerState<PinSetupScreen> createState() => _PinSetupScreenState();
}

class _PinSetupScreenState extends ConsumerState<PinSetupScreen> {
  final _pin = TextEditingController();
  final _confirm = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _pin.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final t = AppL10n.of(context);
    final pin = _pin.text.trim();
    if (pin.length < 4) {
      setState(() => _error = t.pinTooShort);
      return;
    }
    if (pin != _confirm.text.trim()) {
      setState(() => _error = t.pinMismatch);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final v = ref.read(verificationProvider);
    final notifier = ref.read(verificationProvider.notifier);
    try {
      final pass = await ref.read(chatApiProvider).setPin(pin: pin);
      await notifier.applyRotatedPass(pass, v.phone ?? '', v.name ?? '', pinSet: true);
      notifier.dismissPinSetup(pinSet: true);
    } on ChatApiException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.all(24),
              children: [
                Center(child: Image.asset(Brand.logoAsset, width: 96)),
                const SizedBox(height: 24),
                Text(t.pinSetupTitle,
                    style: Theme.of(context).textTheme.titleLarge,
                    textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(t.pinSetupBlurb,
                    style: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(color: scheme.onSurfaceVariant),
                    textAlign: TextAlign.center),
                const SizedBox(height: 24),
                _pinField(_pin, t.pinSetupNewLabel),
                const SizedBox(height: 12),
                _pinField(_confirm, t.pinSetupConfirmLabel, onDone: _save),
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!,
                      style: TextStyle(color: scheme.error),
                      textAlign: TextAlign.center),
                ],
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _busy ? null : _save,
                  style:
                      FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                  child: _busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(t.pinSetupSave),
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => ref
                          .read(verificationProvider.notifier)
                          .dismissPinSetup(),
                  child: Text(t.pinSetupSkip),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _pinField(TextEditingController c, String label, {VoidCallback? onDone}) {
    return TextField(
      controller: c,
      enabled: !_busy,
      obscureText: true,
      keyboardType: TextInputType.number,
      textAlign: TextAlign.center,
      maxLength: 6,
      style: const TextStyle(fontSize: 24, letterSpacing: 10),
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      onSubmitted: onDone == null ? null : (_) => onDone(),
      decoration: InputDecoration(
        counterText: '',
        labelText: label,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
      ),
    );
  }
}

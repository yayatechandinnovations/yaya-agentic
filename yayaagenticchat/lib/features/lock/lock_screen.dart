import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// The everyday login (chat-experience-design.md §5.5): PIN in, fresh pass out.
/// Reached when the short-lived pass has expired on an enrolled device. The
/// escape hatch is always SMS re-verify — a forgotten PIN never strands anyone.
class LockScreen extends ConsumerStatefulWidget {
  const LockScreen({super.key});

  @override
  ConsumerState<LockScreen> createState() => _LockScreenState();
}

class _LockScreenState extends ConsumerState<LockScreen> {
  final _pin = TextEditingController();

  @override
  void dispose() {
    _pin.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final pin = _pin.text.trim();
    if (pin.length < 4) return;
    await ref.read(verificationProvider.notifier).unlock(pin);
    if (mounted) _pin.clear();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final v = ref.watch(verificationProvider);
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Image.asset(Brand.logoAsset, width: 96),
                  const SizedBox(height: 24),
                  Text(
                    v.name == null || v.name!.isEmpty
                        ? t.lockTitle
                        : t.lockTitleNamed(v.name!),
                    style: Theme.of(context).textTheme.titleLarge,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(t.lockBlurb,
                      style: Theme.of(context)
                          .textTheme
                          .bodyMedium
                          ?.copyWith(color: scheme.onSurfaceVariant),
                      textAlign: TextAlign.center),
                  const SizedBox(height: 24),
                  TextField(
                    controller: _pin,
                    autofocus: true,
                    enabled: !v.busy,
                    obscureText: true,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    maxLength: 6,
                    style: const TextStyle(fontSize: 28, letterSpacing: 12),
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    onSubmitted: (_) => _submit(),
                    decoration: InputDecoration(
                      counterText: '',
                      hintText: '••••',
                      border:
                          OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
                      errorText: v.error,
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: v.busy ? null : _submit,
                    style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(48)),
                    child: v.busy
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : Text(t.lockUnlock),
                  ),
                  const SizedBox(height: 12),
                  TextButton(
                    onPressed: v.busy
                        ? null
                        : () =>
                            ref.read(verificationProvider.notifier).reverifyBySms(),
                    child: Text(t.lockForgotPin),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

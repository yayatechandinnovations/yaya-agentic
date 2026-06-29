import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// First-run phone verification (chat-channel-design.md §20). Collects phone +
/// name, sends an SMS code via the backend (Twilio Verify), and on success the
/// app stores a device pass — proving a verified human before any chat starts.
class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({super.key});

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen> {
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(verificationProvider);
    final notifier = ref.read(verificationProvider.notifier);
    final t = AppL10n.of(context);

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(28),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Image.asset(Brand.logoAsset, height: 160),
                const SizedBox(height: 8),
                Text(
                  state.codeSent ? t.onboardingCodeTitle : t.onboardingVerifyTitle,
                  style: Theme.of(context).textTheme.titleLarge,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 6),
                Text(
                  state.codeSent
                      ? t.onboardingCodeBlurb(state.phone ?? '')
                      : t.onboardingVerifyBlurb,
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Theme.of(context).colorScheme.outline),
                ),
                const SizedBox(height: 20),
                if (state.error != null) ...[
                  Text(state.error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                      textAlign: TextAlign.center),
                  const SizedBox(height: 12),
                ],
                if (!state.codeSent) ...[
                  _field(_name, t.fieldFullName, TextInputType.name),
                  _field(_phone, t.fieldPhone, TextInputType.phone),
                  const SizedBox(height: 8),
                  _primary(
                    label: t.sendCode,
                    busy: state.busy,
                    onPressed: () => notifier.start(_phone.text.trim(), _name.text.trim()),
                  ),
                ] else ...[
                  _field(_code, t.fieldCode, TextInputType.number),
                  const SizedBox(height: 8),
                  _primary(
                    label: t.verify,
                    busy: state.busy,
                    onPressed: () => notifier.check(_code.text.trim()),
                  ),
                  TextButton(
                    onPressed: state.busy ? null : notifier.resetCode,
                    child: Text(t.changeNumber),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _field(TextEditingController c, String label, TextInputType kind) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: TextField(
          controller: c,
          keyboardType: kind,
          decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
        ),
      );

  Widget _primary({required String label, required bool busy, required VoidCallback onPressed}) =>
      SizedBox(
        width: double.infinity,
        child: FilledButton(
          onPressed: busy ? null : onPressed,
          child: busy
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(label),
        ),
      );
}

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
                  _title(t, state.step, state.phone),
                  style: Theme.of(context).textTheme.titleLarge,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 6),
                Text(
                  _blurb(t, state.step, state.phone),
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
                ..._stepFields(t, state, notifier),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _title(AppL10n t, VerifyStep step, String? phone) => switch (step) {
        VerifyStep.phone => t.onboardingVerifyTitle,
        VerifyStep.name => t.onboardingNameTitle,
        VerifyStep.code => t.onboardingCodeTitle,
      };

  String _blurb(AppL10n t, VerifyStep step, String? phone) => switch (step) {
        VerifyStep.phone => t.onboardingVerifyBlurb,
        VerifyStep.name => t.onboardingNameBlurb,
        VerifyStep.code => t.onboardingCodeBlurb(phone ?? ''),
      };

  List<Widget> _stepFields(AppL10n t, VerificationState state, VerificationNotifier notifier) =>
      switch (state.step) {
        VerifyStep.phone => [
            _field(_phone, t.fieldPhone, TextInputType.phone),
            const SizedBox(height: 8),
            _primary(
              label: t.continueLabel,
              busy: state.busy,
              onPressed: () => notifier.submitPhone(_phone.text.trim()),
            ),
          ],
        VerifyStep.name => [
            _field(_name, t.fieldFullName, TextInputType.name),
            const SizedBox(height: 8),
            _primary(
              label: t.sendCode,
              busy: state.busy,
              onPressed: () => notifier.submitName(_name.text.trim()),
            ),
            TextButton(
              onPressed: state.busy ? null : notifier.resetToPhone,
              child: Text(t.changeNumber),
            ),
          ],
        VerifyStep.code => [
            _field(_code, t.fieldCode, TextInputType.number),
            const SizedBox(height: 8),
            _primary(
              label: t.verify,
              busy: state.busy,
              onPressed: () => notifier.check(_code.text.trim()),
            ),
            TextButton(
              onPressed: state.busy ? null : notifier.resetToPhone,
              child: Text(t.changeNumber),
            ),
          ],
      };

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

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'assignments_view.dart';
import 'courier_models.dart';
import 'courier_providers.dart';
import 'registration_form.dart';

/// Courier mode (yaya-sales-design.md §11.5): the in-house fleet's screen
/// inside the shopper app. Registration → OTP → documents → merchant review →
/// the working surface. Talks to yaya-sales directly with the courier token;
/// the shopper chat experience is untouched.
class CourierScreen extends ConsumerWidget {
  const CourierScreen({super.key, this.inviteToken});

  /// Pre-filled from the deep link `/courier?invite=…`.
  final String? inviteToken;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(courierProvider);

    // Surface one-shot notices (COD collected, documents submitted) and errors.
    ref.listen<CourierState>(courierProvider, (prev, next) {
      final messenger = ScaffoldMessenger.maybeOf(context);
      if (messenger == null) return;
      if (next.notice != null && next.notice != prev?.notice) {
        messenger.showSnackBar(SnackBar(content: Text(next.notice!)));
        ref.read(courierProvider.notifier).dismissNotice();
      }
    });

    return Scaffold(
      appBar: AppBar(
        title: const Text('Courier'),
        actions: [
          if (state.token != null)
            IconButton(
              tooltip: 'Refresh',
              icon: const Icon(Icons.refresh),
              onPressed: state.busy
                  ? null
                  : () => ref.read(courierProvider.notifier).refreshMe(),
            ),
        ],
      ),
      body: Column(
        children: [
          if (state.error != null)
            MaterialBanner(
              content: Text(state.error!),
              leading: const Icon(Icons.error_outline),
              actions: [
                TextButton(
                  onPressed: () => ref.read(courierProvider.notifier).refreshMe(),
                  child: const Text('Retry'),
                ),
              ],
            ),
          Expanded(child: _body(state)),
        ],
      ),
    );
  }

  Widget _body(CourierState state) {
    switch (state.phase) {
      case CourierPhase.loading:
        return const Center(child: CircularProgressIndicator());
      case CourierPhase.register:
        return CourierRegistrationForm(inviteToken: inviteToken);
      case CourierPhase.otp:
        return const _OtpStep();
      case CourierPhase.docs:
        return const _DocumentsStep();
      case CourierPhase.review:
        return const _ReviewStep();
      case CourierPhase.blocked:
        return const _BlockedStep();
      case CourierPhase.reauth:
        return const _TokenEntryStep();
      case CourierPhase.approved:
        return const AssignmentsView();
    }
  }
}

/* ------------------------------ OTP step -------------------------------- */

class _OtpStep extends ConsumerStatefulWidget {
  const _OtpStep();

  @override
  ConsumerState<_OtpStep> createState() => _OtpStepState();
}

class _OtpStepState extends ConsumerState<_OtpStep> {
  final _otp = TextEditingController();
  late final TextEditingController _phone;

  @override
  void initState() {
    super.initState();
    _phone = TextEditingController(text: ref.read(courierProvider).phone ?? '');
  }

  @override
  void dispose() {
    _otp.dispose();
    _phone.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(courierProvider);
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Verify your phone', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        const Text('Enter the code sent to your phone to finish registering.'),
        if (state.otpEcho != null) ...[
          const SizedBox(height: 8),
          Text('Dev code: ${state.otpEcho}',
              style: Theme.of(context).textTheme.bodySmall),
        ],
        const SizedBox(height: 20),
        TextField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          decoration:
              const InputDecoration(labelText: 'Phone', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _otp,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
              labelText: 'Verification code', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 20),
        FilledButton(
          onPressed: state.busy
              ? null
              : () => ref
                  .read(courierProvider.notifier)
                  .verifyOtp(_phone.text.trim(), _otp.text.trim()),
          child: const Text('Verify'),
        ),
      ],
    );
  }
}

/* --------------------------- documents step ------------------------------ */

class _DocumentsStep extends ConsumerStatefulWidget {
  const _DocumentsStep();

  @override
  ConsumerState<_DocumentsStep> createState() => _DocumentsStepState();
}

class _DocumentsStepState extends ConsumerState<_DocumentsStep> {
  final Map<String, TextEditingController> _content = {
    for (final kind in courierDocKinds) kind: TextEditingController(),
  };

  @override
  void dispose() {
    for (final c in _content.values) {
      c.dispose();
    }
    super.dispose();
  }

  String _label(String kind) {
    switch (kind) {
      case 'PHOTO_ID_FRONT':
        return 'Photo ID — front';
      case 'PHOTO_ID_BACK':
        return 'Photo ID — back';
      case 'DRIVER_LICENSE':
        return 'Driver license';
      case 'VEHICLE_REGISTRATION':
        return 'Vehicle registration';
      case 'INSURANCE':
        return 'Insurance';
      default:
        return kind;
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(courierProvider);
    final uploaded = {for (final d in state.me?.documents ?? const <CourierDocument>[]) d.kind};

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Upload your documents', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        const Text('The store reviews these before approving you. Required: a photo ID '
            '(front or driver license) AND a vehicle document (registration or insurance).\n\n'
            'Enter each document\'s reference details below (v1 — photo capture is '
            'coming). Everything is stored encrypted.'),
        const SizedBox(height: 16),
        for (final kind in courierDocKinds) ...[
          Row(
            children: [
              Expanded(child: Text(_label(kind), style: Theme.of(context).textTheme.titleSmall)),
              if (uploaded.contains(kind))
                const Icon(Icons.check_circle, size: 18, color: Colors.green),
            ],
          ),
          const SizedBox(height: 6),
          TextField(
            controller: _content[kind],
            maxLines: 2,
            decoration: InputDecoration(
              hintText: uploaded.contains(kind)
                  ? 'Already submitted — enter to replace'
                  : 'Document number / issuing details',
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 14),
        ],
        FilledButton(
          onPressed: state.busy
              ? null
              : () => ref.read(courierProvider.notifier).submitDocuments(
                  {for (final e in _content.entries) e.key: e.value.text}),
          child: state.busy
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Submit documents'),
        ),
      ],
    );
  }
}

/* ----------------------------- review step ------------------------------- */

class _ReviewStep extends ConsumerWidget {
  const _ReviewStep();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(courierProvider);
    return RefreshIndicator(
      onRefresh: () => ref.read(courierProvider.notifier).refreshMe(),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(24),
        children: [
          const SizedBox(height: 40),
          const Icon(Icons.hourglass_top, size: 48),
          const SizedBox(height: 16),
          Text('Under review', textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          const Text(
            'The store is reviewing your documents. Once approved, they will hand '
            'you a NEW access token — your current one stops working at that '
            'moment. Come back here and enter it to start delivering.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 24),
          OutlinedButton.icon(
            onPressed: state.busy
                ? null
                : () => ref.read(courierProvider.notifier).refreshMe(),
            icon: const Icon(Icons.refresh),
            label: const Text('Check status'),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => ref.read(courierProvider.notifier).showTokenEntry(),
            child: const Text('I received my new access token'),
          ),
        ],
      ),
    );
  }
}

/* ----------------------------- blocked step ------------------------------ */

class _BlockedStep extends ConsumerWidget {
  const _BlockedStep();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(courierProvider);
    final me = state.me;
    final suspended = me?.status == 'SUSPENDED';
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Icon(suspended ? Icons.pause_circle_outline : Icons.block, size: 48),
          const SizedBox(height: 16),
          Text(suspended ? 'Account suspended' : 'Registration declined',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge),
          if (me?.reason != null) ...[
            const SizedBox(height: 8),
            Text(me!.reason!, textAlign: TextAlign.center),
          ],
          const SizedBox(height: 8),
          const Text('Contact the store for details.', textAlign: TextAlign.center),
          const SizedBox(height: 24),
          TextButton(
            onPressed: () => ref.read(courierProvider.notifier).exitCourierMode(),
            child: const Text('Start over'),
          ),
        ],
      ),
    );
  }
}

/* --------------------------- token entry step ---------------------------- */

/// Approval ROTATES the credential (yaya-sales-design.md §10.4): the
/// operational token is minted at approval and shown to the merchant, who
/// hands it to the courier out-of-band. Any 401 lands here.
class _TokenEntryStep extends ConsumerStatefulWidget {
  const _TokenEntryStep();

  @override
  ConsumerState<_TokenEntryStep> createState() => _TokenEntryStepState();
}

class _TokenEntryStepState extends ConsumerState<_TokenEntryStep> {
  final _token = TextEditingController();

  @override
  void dispose() {
    _token.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(courierProvider);
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Enter your access token', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        const Text(
          'Paste the courier access token the store gave you. If you were just '
          'approved, this is the NEW token — the one from registration no longer works.',
        ),
        const SizedBox(height: 20),
        TextField(
          controller: _token,
          decoration: const InputDecoration(
              labelText: 'Access token', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 20),
        FilledButton(
          onPressed: state.busy
              ? null
              : () => ref.read(courierProvider.notifier).adoptToken(_token.text),
          child: const Text('Continue'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: state.busy
              ? null
              : () => ref.read(courierProvider.notifier).showRegistration(),
          child: const Text('New courier? Register with an invite'),
        ),
      ],
    );
  }
}

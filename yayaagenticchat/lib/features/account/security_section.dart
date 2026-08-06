import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// Account → Security (chat-experience-design.md §5.5): set/change/remove the
/// PIN, manage this account's devices (per-device PIN gate + revoke), and log
/// out of just this device.
class SecuritySection extends ConsumerWidget {
  const SecuritySection({super.key, required this.account});

  final Account account;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final pinSet = ref.watch(verificationProvider.select((s) => s.pinSet)) || account.pinSet;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
          child: Text(t.securitySection,
              style: Theme.of(context)
                  .textTheme
                  .labelLarge
                  ?.copyWith(color: Theme.of(context).colorScheme.primary)),
        ),
        ListTile(
          leading: const Icon(Icons.pin_outlined),
          title: Text(t.securityPin),
          subtitle: Text(pinSet ? t.securityPinOn : t.securityPinOff),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _setOrChangePin(context, ref, pinSet),
        ),
        if (pinSet)
          ListTile(
            leading: const Icon(Icons.pin_end),
            title: Text(t.securityPinRemove),
            onTap: () => _removePin(context, ref),
          ),
        ListTile(
          leading: const Icon(Icons.devices_outlined),
          title: Text(t.securityDevices),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _showDevices(context, ref, pinSet),
        ),
        ListTile(
          leading: const Icon(Icons.logout),
          title: Text(t.securityLogoutDevice),
          onTap: () => _logoutThisDevice(context, ref),
        ),
      ],
    );
  }

  /* ------------------------------- PIN ---------------------------------- */

  Future<void> _setOrChangePin(BuildContext context, WidgetRef ref, bool pinSet) async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final result = await showDialog<_PinDialogResult>(
      context: context,
      builder: (_) => _PinDialog(
        title: pinSet ? t.securityPinChange : t.securityPinSetTitle,
        askCurrent: pinSet,
        askNew: true,
      ),
    );
    if (result == null) return;
    final v = ref.read(verificationProvider);
    try {
      final pass = await ref
          .read(chatApiProvider)
          .setPin(currentPin: result.currentPin, pin: result.newPin!);
      await ref.read(verificationProvider.notifier).applyRotatedPass(
          pass, v.phone ?? '', v.name ?? '',
          pinSet: true);
      ref.invalidate(accountProvider);
      messenger.showSnackBar(SnackBar(content: Text(t.securityPinSaved)));
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_pinError(t, e))));
    }
  }

  Future<void> _removePin(BuildContext context, WidgetRef ref) async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final result = await showDialog<_PinDialogResult>(
      context: context,
      builder: (_) => _PinDialog(
        title: t.securityPinRemove,
        blurb: t.securityPinRemoveBlurb,
        askCurrent: true,
        askNew: false,
      ),
    );
    if (result == null) return;
    final v = ref.read(verificationProvider);
    try {
      final pass = await ref.read(chatApiProvider).removePin(result.currentPin!);
      await ref
          .read(verificationProvider.notifier)
          .applyRotatedPass(pass, v.phone ?? '', v.name ?? '');
      ref.read(verificationProvider.notifier).dismissPinSetup();
      ref.invalidate(accountProvider);
      messenger.showSnackBar(SnackBar(content: Text(t.securityPinRemoved)));
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_pinError(t, e))));
    }
  }

  static String _pinError(AppL10n t, ChatApiException e) =>
      e.code == 'pin_denied' || e.code == 'otp_required' ? t.securityPinWrong : e.message;

  /* ------------------------------ devices -------------------------------- */

  void _showDevices(BuildContext context, WidgetRef ref, bool pinSet) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => _DevicesSheet(pinSet: pinSet),
    );
  }

  Future<void> _logoutThisDevice(BuildContext context, WidgetRef ref) async {
    final t = AppL10n.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.securityLogoutDevice),
        content: Text(t.securityLogoutDeviceBlurb),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(t.cancel)),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(t.securityLogoutDevice)),
        ],
      ),
    );
    if (confirmed != true) return;
    final deviceId = ref.read(deviceIdProvider);
    try {
      await ref.read(chatApiProvider).revokeDevice(deviceId);
    } on ChatApiException catch (_) {
      // Local logout proceeds either way; the pass simply expires server-side.
    }
    await ref.read(verificationProvider.notifier).signOutThisDevice();
  }
}

/* --------------------------- devices sheet ------------------------------- */

class _DevicesSheet extends ConsumerStatefulWidget {
  const _DevicesSheet({required this.pinSet});

  final bool pinSet;

  @override
  ConsumerState<_DevicesSheet> createState() => _DevicesSheetState();
}

class _DevicesSheetState extends ConsumerState<_DevicesSheet> {
  List<AccountDevice>? _devices;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final devices = await ref.read(chatApiProvider).listDevices();
      if (mounted) setState(() => _devices = devices);
    } on ChatApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _togglePinGate(AccountDevice d, bool required) async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    String? currentPin;
    // Turning the gate OFF is a step-up: prove the PIN first (§5.4).
    if (!required && widget.pinSet) {
      final result = await showDialog<_PinDialogResult>(
        context: context,
        builder: (_) => _PinDialog(
          title: t.securityDeviceTrustTitle,
          blurb: t.securityDeviceTrustBlurb,
          askCurrent: true,
          askNew: false,
        ),
      );
      if (result == null) return;
      currentPin = result.currentPin;
    }
    try {
      await ref
          .read(chatApiProvider)
          .updateDevice(d.deviceId, pinRequired: required, currentPin: currentPin);
      if (d.current) {
        await ref.read(directoryStoreProvider).savePinRequired(required);
      }
      await _load();
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text(e.code == 'pin_denied' ? t.securityPinWrong : e.message)));
    }
  }

  Future<void> _revoke(AccountDevice d) async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(chatApiProvider).revokeDevice(d.deviceId);
      if (d.current) {
        if (mounted) Navigator.of(context).pop();
        await ref.read(verificationProvider.notifier).signOutThisDevice();
        return;
      }
      messenger.showSnackBar(SnackBar(content: Text(t.securityDeviceRevoked)));
      await _load();
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.6,
      builder: (context, controller) {
        Widget body;
        if (_error != null) {
          body = Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!)));
        } else if (_devices == null) {
          body = const Center(child: CircularProgressIndicator());
        } else if (_devices!.isEmpty) {
          body = Center(child: Text(t.securityDevicesEmpty));
        } else {
          body = ListView.builder(
            controller: controller,
            itemCount: _devices!.length,
            itemBuilder: (context, i) {
              final d = _devices![i];
              return ListTile(
                leading: Icon(d.current ? Icons.smartphone : Icons.devices_other_outlined),
                title: Text(
                  d.current
                      ? t.securityDeviceThis
                      : (d.label == null || d.label!.isEmpty
                          ? t.securityDeviceUnnamed
                          : d.label!),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (d.lastSeenAt != null)
                      Text(t.securityDeviceLastSeen(_shortDate(d.lastSeenAt!)),
                          style: Theme.of(context).textTheme.bodySmall),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(t.securityDeviceAskPin,
                            style: Theme.of(context).textTheme.bodySmall),
                        Switch(
                          value: d.pinRequired,
                          onChanged: widget.pinSet
                              ? (next) => _togglePinGate(d, next)
                              : null,
                        ),
                      ],
                    ),
                  ],
                ),
                trailing: IconButton(
                  icon: const Icon(Icons.logout),
                  tooltip: t.securityDeviceRevoke,
                  onPressed: () => _revoke(d),
                ),
                isThreeLine: d.lastSeenAt != null,
              );
            },
          );
        }
        return Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Text(t.securityDevices,
                  style: Theme.of(context).textTheme.titleMedium),
            ),
            Expanded(child: body),
          ],
        );
      },
    );
  }

  static String _shortDate(String iso) {
    final dt = DateTime.tryParse(iso);
    if (dt == null) return iso;
    final local = dt.toLocal();
    return '${local.year}-${local.month.toString().padLeft(2, '0')}-${local.day.toString().padLeft(2, '0')}';
  }
}

/* ----------------------------- PIN dialog -------------------------------- */

class _PinDialogResult {
  final String? currentPin;
  final String? newPin;
  const _PinDialogResult({this.currentPin, this.newPin});
}

class _PinDialog extends StatefulWidget {
  const _PinDialog({
    required this.title,
    this.blurb,
    required this.askCurrent,
    required this.askNew,
  });

  final String title;
  final String? blurb;
  final bool askCurrent;
  final bool askNew;

  @override
  State<_PinDialog> createState() => _PinDialogState();
}

class _PinDialogState extends State<_PinDialog> {
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
    super.dispose();
  }

  void _submit() {
    final t = AppL10n.of(context);
    if (widget.askCurrent && _current.text.trim().length < 4) {
      setState(() => _error = t.pinTooShort);
      return;
    }
    if (widget.askNew) {
      if (_next.text.trim().length < 4) {
        setState(() => _error = t.pinTooShort);
        return;
      }
      if (_next.text.trim() != _confirm.text.trim()) {
        setState(() => _error = t.pinMismatch);
        return;
      }
    }
    Navigator.pop(
      context,
      _PinDialogResult(
        currentPin: widget.askCurrent ? _current.text.trim() : null,
        newPin: widget.askNew ? _next.text.trim() : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (widget.blurb != null) ...[
            Text(widget.blurb!, style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 12),
          ],
          if (widget.askCurrent)
            _field(_current, t.securityPinCurrentLabel),
          if (widget.askNew) ...[
            const SizedBox(height: 8),
            _field(_next, t.pinSetupNewLabel),
            const SizedBox(height: 8),
            _field(_confirm, t.pinSetupConfirmLabel, onDone: _submit),
          ],
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(onPressed: _submit, child: Text(t.ok)),
      ],
    );
  }

  Widget _field(TextEditingController c, String label, {VoidCallback? onDone}) {
    return TextField(
      controller: c,
      obscureText: true,
      keyboardType: TextInputType.number,
      maxLength: 6,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      onSubmitted: onDone == null ? null : (_) => onDone(),
      decoration: InputDecoration(
        counterText: '',
        labelText: label,
        border: const OutlineInputBorder(),
      ),
    );
  }
}

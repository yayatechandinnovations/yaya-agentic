import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';

/// The three-step phone-change wizard (chat-account-self-service-design.md §6.2):
/// (1) enter the new number → OTP to the CURRENT number; (2) verify the OLD
/// number's code → OTP to the NEW number; (3) verify the NEW number's code →
/// migrate. There is deliberately NO self-serve bypass for a lost old number
/// (decision §14.1) — the old-code step points the user to support instead.
enum _Step { newNumber, oldCode, newCode }

class ChangePhoneFlow extends ConsumerStatefulWidget {
  const ChangePhoneFlow({super.key});

  @override
  ConsumerState<ChangePhoneFlow> createState() => _ChangePhoneFlowState();
}

class _ChangePhoneFlowState extends ConsumerState<ChangePhoneFlow> {
  final _newPhone = TextEditingController();
  final _code = TextEditingController();

  _Step _step = _Step.newNumber;
  String? _changeId;
  String _newPhoneE164 = '';
  bool _busy = false;
  String? _error;

  ChatApi get _api => ref.read(chatApiProvider);

  @override
  void dispose() {
    _newPhone.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final phone = _newPhone.text.trim();
    if (phone.isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await _api.phoneChangeStart(phone);
      if (!mounted) return;
      setState(() {
        _changeId = res.changeId;
        _newPhoneE164 = phone;
        _step = _Step.oldCode;
        _code.clear();
        _busy = false;
      });
    } on ChatApiException catch (e) {
      _fail(e);
    }
  }

  Future<void> _verifyOld() async {
    if (_changeId == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _api.phoneChangeVerifyOld(_changeId!, _code.text.trim());
      if (!mounted) return;
      setState(() {
        _step = _Step.newCode;
        _code.clear();
        _busy = false;
      });
    } on ChatApiException catch (e) {
      _fail(e);
    }
  }

  Future<void> _verifyNew() async {
    if (_changeId == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await _api.phoneChangeVerifyNew(_changeId!, _code.text.trim());
      // Migration done: adopt the fresh pass + new phone, keeping the name (§6.2).
      final name = ref.read(verificationProvider).name ?? '';
      await ref
          .read(verificationProvider.notifier)
          .applyRotatedPass(res.devicePass, res.phoneE164, name);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on ChatApiException catch (e) {
      _fail(e);
    }
  }

  Future<void> _resend() async {
    if (_changeId == null) return;
    final messenger = ScaffoldMessenger.of(context);
    final t = AppL10n.of(context);
    try {
      await _api.phoneChangeResend(_changeId!);
      messenger.showSnackBar(SnackBar(content: Text(t.codeResent)));
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  void _fail(ChatApiException e) {
    if (!mounted) return;
    setState(() {
      _busy = false;
      _error = e.message;
    });
  }

  /// Leaving mid-ceremony aborts it server-side so it can't linger (§6.2).
  Future<bool> _confirmLeave() async {
    if (_changeId != null) {
      await _api.phoneChangeAbort(_changeId!);
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final navigator = Navigator.of(context);
        await _confirmLeave();
        navigator.pop(false);
      },
      child: Scaffold(
        appBar: AppBar(title: Text(t.changePhoneNumber)),
        body: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(_title(t), style: Theme.of(context).textTheme.titleLarge, textAlign: TextAlign.center),
                  const SizedBox(height: 6),
                  Text(_blurb(t),
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Theme.of(context).colorScheme.outline)),
                  const SizedBox(height: 20),
                  if (_error != null) ...[
                    Text(_error!,
                        style: TextStyle(color: Theme.of(context).colorScheme.error),
                        textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                  ],
                  ..._fields(t),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _title(AppL10n t) => switch (_step) {
        _Step.newNumber => t.changePhoneNewNumberTitle,
        _Step.oldCode => t.changePhoneOldCodeTitle,
        _Step.newCode => t.changePhoneNewCodeTitle,
      };

  String _blurb(AppL10n t) => switch (_step) {
        _Step.newNumber => t.changePhoneNewNumberBlurb,
        _Step.oldCode => t.changePhoneOldCodeBlurb,
        _Step.newCode => t.changePhoneNewCodeBlurb(_newPhoneE164),
      };

  List<Widget> _fields(AppL10n t) => switch (_step) {
        _Step.newNumber => [
            _field(_newPhone, t.fieldNewPhone, TextInputType.phone),
            const SizedBox(height: 8),
            _primary(t.next, _start),
          ],
        _Step.oldCode => [
            _field(_code, t.fieldCode, TextInputType.number),
            const SizedBox(height: 8),
            _primary(t.verify, _verifyOld),
            TextButton(onPressed: _busy ? null : _resend, child: Text(t.resend)),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(t.lostOldNumber,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.outline)),
            ),
          ],
        _Step.newCode => [
            _field(_code, t.fieldCode, TextInputType.number),
            const SizedBox(height: 8),
            _primary(t.verify, _verifyNew),
            TextButton(onPressed: _busy ? null : _resend, child: Text(t.resend)),
          ],
      };

  Widget _field(TextEditingController c, String label, TextInputType kind) => TextField(
        controller: c,
        keyboardType: kind,
        enabled: !_busy,
        decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
      );

  Widget _primary(String label, VoidCallback onPressed) => FilledButton(
        onPressed: _busy ? null : onPressed,
        child: _busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : Text(label),
      );
}

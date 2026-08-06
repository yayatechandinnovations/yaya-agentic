import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import 'change_phone_flow.dart';
import 'delete_account_dialog.dart';
import 'security_section.dart';

/// The end-user account settings screen (chat-account-self-service-design.md §6,
/// §10). Shows the phone (read-only) and name (inline-editable), plus the
/// change-phone, delete, and sign-out actions.
class AccountScreen extends ConsumerStatefulWidget {
  const AccountScreen({super.key});

  @override
  ConsumerState<AccountScreen> createState() => _AccountScreenState();
}

class _AccountScreenState extends ConsumerState<AccountScreen> {
  final _name = TextEditingController();
  bool _editing = false;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _saveName(String phone) async {
    final next = _name.text.trim();
    if (next.isEmpty) return;
    setState(() => _saving = true);
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final res = await ref.read(chatApiProvider).renameAccount(next);
      // Rename re-mints the pass (phone unchanged); adopt it so later calls carry
      // the new name and identity (§6.1).
      await ref
          .read(verificationProvider.notifier)
          .applyRotatedPass(res.devicePass, phone, res.name ?? next);
      if (!mounted) return;
      setState(() {
        _editing = false;
        _saving = false;
      });
      messenger.showSnackBar(SnackBar(content: Text(t.nameUpdated)));
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  Future<void> _changePhone() async {
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const ChangePhoneFlow()),
    );
    if (changed == true && mounted) {
      ref.invalidate(accountProvider);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(AppL10n.of(context).phoneChanged)));
    }
  }

  Future<void> _signOut() async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.signOutConfirmTitle),
        content: Text(t.signOutConfirmBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(t.cancel)),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(t.signOut)),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await ref.read(chatApiProvider).signOutEverywhere();
    } on ChatApiException catch (_) {
      // The pass is revoked either way; proceed to a local sign-out.
    }
    // Drops all local state → the app shell rebuilds into onboarding.
    await ref.read(verificationProvider.notifier).signOut();
    messenger.showSnackBar(SnackBar(content: Text(t.signOut)));
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final account = ref.watch(accountProvider);

    return Scaffold(
      appBar: AppBar(title: Text(t.account)),
      body: account.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(e is ChatApiException ? e.message : t.chatUnavailable,
                textAlign: TextAlign.center),
          ),
        ),
        data: (acct) => ListView(
          padding: const EdgeInsets.symmetric(vertical: 12),
          children: [
            _phoneTile(t, acct),
            _nameTile(t, acct),
            const Divider(height: 24),
            SecuritySection(account: acct),
            const Divider(height: 24),
            ListTile(
              leading: const Icon(Icons.receipt_long_outlined),
              title: Text(t.receiptsTitle),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.go('/account/receipts'),
            ),
            ListTile(
              leading: const Icon(Icons.sim_card_outlined),
              title: Text(t.changePhoneNumber),
              trailing: const Icon(Icons.chevron_right),
              onTap: _changePhone,
            ),
            ListTile(
              leading: Icon(Icons.delete_outline, color: Theme.of(context).colorScheme.error),
              title: Text(t.deleteAccount,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
              onTap: () => showDeleteAccountDialog(context, ref),
            ),
            ListTile(
              leading: const Icon(Icons.logout),
              title: Text(t.signOut),
              onTap: _signOut,
            ),
          ],
        ),
      ),
    );
  }

  Widget _phoneTile(AppL10n t, Account acct) => ListTile(
        leading: const Icon(Icons.phone_outlined),
        title: Text(t.accountPhoneLabel),
        subtitle: Text(acct.phoneE164),
      );

  Widget _nameTile(AppL10n t, Account acct) {
    if (!_editing) {
      return ListTile(
        leading: const Icon(Icons.person_outline),
        title: Text(t.accountNameLabel),
        subtitle: Text(acct.name.isEmpty ? '—' : acct.name),
        trailing: IconButton(
          icon: const Icon(Icons.edit_outlined),
          onPressed: () {
            _name.text = acct.name;
            setState(() => _editing = true);
          },
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _name,
              autofocus: true,
              enabled: !_saving,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _saveName(acct.phoneE164),
              decoration: InputDecoration(
                labelText: t.accountNameLabel,
                border: const OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            icon: _saving
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.check),
            onPressed: _saving ? null : () => _saveName(acct.phoneE164),
          ),
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: _saving ? null : () => setState(() => _editing = false),
          ),
        ],
      ),
    );
  }
}

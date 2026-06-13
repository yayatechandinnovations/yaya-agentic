import 'dart:convert';
import 'dart:html' as html;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/selected_tenant.dart';
import '../../models/act_as.dart';

/// "Act as" panel for the playground profile picker. See
/// docs/design/playground-actas-auth-design.md §8.
///
/// v1 ships two modes:
///  - Anonymous (default) — the runtime resolves to an anonymous Principal
///    (matches the old behavior); any HTTP tool with PRINCIPAL_TOKEN will
///    fail against tenants that require a real bearer.
///  - Paste a token — operator supplies a Bearer/Basic credential. The
///    backend SessionController materializes it into `Authorization` on the
///    runtime AuthContext, replacing the operator session cookie.
///
/// State persists to `window.sessionStorage` keyed by tenant — cleared on
/// tab close. Never localStorage; we are not a credential vault.

enum ActAsMode { anonymous, rawToken }

class ActAsDraft {
  const ActAsDraft({
    this.mode = ActAsMode.anonymous,
    this.scheme = 'Bearer',
    this.token = '',
  });

  final ActAsMode mode;
  final String scheme;
  final String token;

  ActAsDraft copyWith({ActAsMode? mode, String? scheme, String? token}) =>
      ActAsDraft(
        mode: mode ?? this.mode,
        scheme: scheme ?? this.scheme,
        token: token ?? this.token,
      );

  /// Build the wire-format [ActAs] for the start-session request, or null
  /// when the operator chose Anonymous (or the form is incomplete).
  ActAs? toActAs() {
    return switch (mode) {
      ActAsMode.anonymous => null,
      ActAsMode.rawToken =>
        token.trim().isEmpty ? null : ActAs.rawToken(scheme: scheme, token: token.trim()),
    };
  }

  Map<String, dynamic> toJson() =>
      {'mode': mode.name, 'scheme': scheme, 'token': token};

  static ActAsDraft fromJson(Map<String, dynamic> json) {
    final modeStr = json['mode'] as String? ?? 'anonymous';
    final mode = ActAsMode.values.firstWhere(
      (m) => m.name == modeStr,
      orElse: () => ActAsMode.anonymous,
    );
    return ActAsDraft(
      mode: mode,
      scheme: json['scheme'] as String? ?? 'Bearer',
      token: json['token'] as String? ?? '',
    );
  }
}

String _storageKey(String tenant) => 'playground:actAs:$tenant';

class ActAsDraftController extends Notifier<ActAsDraft> {
  @override
  ActAsDraft build() {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const ActAsDraft();
    final raw = html.window.sessionStorage[_storageKey(tenant)];
    if (raw == null) return const ActAsDraft();
    try {
      return ActAsDraft.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return const ActAsDraft();
    }
  }

  void update(ActAsDraft draft) {
    state = draft;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    html.window.sessionStorage[_storageKey(tenant)] = jsonEncode(draft.toJson());
  }

  void clear() {
    state = const ActAsDraft();
    final tenant = ref.read(currentTenantOrNull);
    if (tenant != null) {
      html.window.sessionStorage.remove(_storageKey(tenant));
    }
  }
}

final actAsDraftProvider =
    NotifierProvider<ActAsDraftController, ActAsDraft>(ActAsDraftController.new);

class ActAsPanel extends ConsumerWidget {
  const ActAsPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final draft = ref.watch(actAsDraftProvider);
    final notifier = ref.read(actAsDraftProvider.notifier);
    final scheme = Theme.of(context).colorScheme;

    return Card(
      margin: EdgeInsets.zero,
      color: scheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Act as',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 2),
            Text(
              'How should the session authenticate to the tenant app? '
              'Pick Anonymous to skip; paste a token when a profile calls '
              'HTTP tools that require a real user.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            RadioListTile<ActAsMode>(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('Anonymous'),
              subtitle: const Text(
                  'Runtime resolves to an anonymous Principal. HTTP tools '
                  'with PRINCIPAL_TOKEN will 403 if the tenant requires one.'),
              value: ActAsMode.anonymous,
              groupValue: draft.mode,
              onChanged: (m) => m == null ? null : notifier.update(draft.copyWith(mode: m)),
            ),
            RadioListTile<ActAsMode>(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('Paste a token'),
              subtitle: const Text(
                  'Forwarded as Authorization. Never persisted to localStorage; '
                  'cleared when this tab closes.'),
              value: ActAsMode.rawToken,
              groupValue: draft.mode,
              onChanged: (m) => m == null ? null : notifier.update(draft.copyWith(mode: m)),
            ),
            if (draft.mode == ActAsMode.rawToken) ...[
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 110,
                    child: DropdownButtonFormField<String>(
                      initialValue: draft.scheme,
                      decoration: const InputDecoration(labelText: 'scheme'),
                      items: const [
                        DropdownMenuItem(value: 'Bearer', child: Text('Bearer')),
                        DropdownMenuItem(value: 'Basic', child: Text('Basic')),
                      ],
                      onChanged: (v) => v == null
                          ? null
                          : notifier.update(draft.copyWith(scheme: v)),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      initialValue: draft.token,
                      decoration: const InputDecoration(
                        labelText: 'token',
                        hintText: 'eyJhbGc…   or   base64(user:pass)',
                      ),
                      obscureText: true,
                      autocorrect: false,
                      enableSuggestions: false,
                      onChanged: (v) => notifier.update(draft.copyWith(token: v)),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Compact banner shown above the playground chat while an act-as session
/// is active. Lets the operator see at a glance whose credential is in use.
class ActAsActiveBanner extends StatelessWidget {
  const ActAsActiveBanner({super.key, required this.draft});
  final ActAsDraft draft;

  @override
  Widget build(BuildContext context) {
    if (draft.mode == ActAsMode.anonymous || draft.token.trim().isEmpty) {
      return const SizedBox.shrink();
    }
    final scheme = Theme.of(context).colorScheme;
    final preview = _tokenPreview(draft.token);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      color: scheme.tertiaryContainer,
      child: Row(
        children: [
          Icon(Icons.vpn_key, size: 16, color: scheme.onTertiaryContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Acting as supplied ${draft.scheme} token · $preview',
              style: TextStyle(
                  color: scheme.onTertiaryContainer,
                  fontSize: 12,
                  fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  static String _tokenPreview(String token) {
    final trimmed = token.trim();
    if (trimmed.length <= 10) return '••••${trimmed.substring(trimmed.length ~/ 2)}';
    return '••••${trimmed.substring(trimmed.length - 6)}';
  }
}

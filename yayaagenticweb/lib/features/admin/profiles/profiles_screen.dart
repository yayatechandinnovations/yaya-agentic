import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_binding.dart';
import '../../../models/admin/capability.dart';
import '../../../models/admin/profile.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import 'profile_form.dart';

/// Empty list when no tenant is selected — the scoped screen renders a
/// "register a tenant first" banner via [TenantScopedEmptyState] instead
/// of dispatching against `default` and confusing the operator.
final profilesProvider = FutureProvider<List<ProfileResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.profile);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listProfiles(tenant: tenant);
});

final capabilitiesProvider = FutureProvider<List<CapabilityResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.capability);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listCapabilities(tenant: tenant);
});

final authBindingsProvider = FutureProvider<List<AuthBindingResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.authBinding);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listAuthBindings(tenant: tenant);
});

/// Latest version of every profile, with its version history.
final profileSummariesProvider = Provider<AsyncValue<List<ProfileSummary>>>(
  (ref) => ref.watch(profilesProvider).whenData(summarizeProfiles),
);

class ProfilesScreen extends ConsumerWidget {
  const ProfilesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final params = GoRouterState.of(context).uri.queryParameters;
    // Old links and the palette's generic form: ?id= / ?new=1.
    if (params['id'] != null || params['new'] != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        context.go(params['id'] != null ? '/admin/profiles/${Uri.encodeComponent(params['id']!)}' : '/admin/profiles/new');
      });
    }
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Profiles');
    }
    final summaries = ref.watch(profileSummariesProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.profile));

    void openNew() => context.go('/admin/profiles/new');

    return YPage(
      title: 'Profiles',
      subtitle: 'The roles users talk to. A profile pins the instructions, the capabilities the agent may use, and how users sign in.',
      actions: [FilledButton.icon(onPressed: openNew, icon: const Icon(Icons.add, size: 18), label: const Text('New profile'))],
      child: LiveAsyncView<List<ProfileSummary>>(
        value: summaries,
        what: 'profiles',
        onRetry: () => ref.invalidate(profilesProvider),
        data: (context, list) => YTable<ProfileSummary>(
          rows: list,
          noun: 'profiles',
          idOf: (s) => s.latest.id,
          searchHint: 'Search by name, ID or capability',
          searchTextOf: (s) => '${s.latest.displayName} ${s.latest.id} ${s.latest.capabilities.join(' ')} ${s.latest.authBindingId ?? ''}',
          highlightIds: fresh,
          onRowTap: (s) => context.go('/admin/profiles/${Uri.encodeComponent(s.latest.id)}'),
          filters: [
            YFilter('Sells from a store', (s) => storeExtensionOf(s.latest.extensions) != null),
            YFilter('Metered', (s) => s.latest.metered),
            YFilter('No sign-in binding', (s) => (s.latest.authBindingId ?? '').isEmpty),
            YFilter('No capabilities', (s) => s.latest.capabilities.isEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.badge_outlined,
            title: 'No profiles yet',
            message: 'A profile is a role users talk to — its instructions, the capabilities it may use, and how users sign in.',
            action: FilledButton.icon(onPressed: openNew, icon: const Icon(Icons.add, size: 18), label: const Text('Create your first profile')),
          ),
          columns: [
            YColumn(
              label: 'Profile',
              flex: 3,
              sortKey: (s) => s.latest.displayName.toLowerCase(),
              cell: (context, s) => _NameCell(profile: s.latest),
            ),
            YColumn(
              label: 'Capabilities',
              flex: 2,
              sortKey: (s) => s.latest.capabilities.length,
              cell: (context, s) {
                final caps = s.latest.capabilities;
                return Text(
                  caps.isEmpty ? 'None' : caps.length == 1 ? caps.first : '${caps.length} capabilities',
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 14, color: caps.isEmpty ? Theme.of(context).colorScheme.onSurfaceVariant : null),
                );
              },
            ),
            YColumn(
              label: 'Sign-in',
              flex: 2,
              sortKey: (s) => s.latest.authBindingId ?? '',
              cell: (context, s) {
                final b = s.latest.authBindingId;
                return b == null || b.isEmpty
                    ? const YBadge('None')
                    : Text(b, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 13));
              },
            ),
            YColumn(
              label: 'Language',
              width: 130,
              sortKey: (s) => s.latest.language,
              cell: (context, s) => Text(profileLanguages[s.latest.language] ?? s.latest.language, style: const TextStyle(fontSize: 14)),
            ),
            YColumn(
              label: 'Version',
              width: 110,
              sortKey: (s) => s.latest.version,
              cell: (context, s) => YCellStack(
                primary: 'v${s.latest.version}',
                mono: true,
                secondary: s.versions.length == 1 ? null : '${s.versions.length} versions',
              ),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.latest.createdAt ?? '',
              cell: (context, s) {
                final isFresh = fresh.contains(s.latest.id);
                return Text(
                  isFresh ? 'Just now' : formatRelativeIso(s.latest.createdAt),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: isFresh ? FontWeight.w600 : null,
                    color: isFresh ? Theme.of(context).colorScheme.primary : null,
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _NameCell extends StatelessWidget {
  const _NameCell({required this.profile});
  final ProfileResponse profile;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = profile.status;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      Row(children: [
        Flexible(
          child: Text(profile.displayName,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface)),
        ),
        if (status != null && status.isNotEmpty && status != 'ACTIVE') ...[
          const SizedBox(width: 8),
          YBadge(status[0] + status.substring(1).toLowerCase(), tone: YTone.warning),
        ],
        if (storeExtensionOf(profile.extensions) != null) ...[
          const SizedBox(width: 8),
          const YBadge('Store', tone: YTone.brand, icon: Icons.storefront_outlined),
        ],
      ]),
      Text(profile.id, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant)),
    ]);
  }
}

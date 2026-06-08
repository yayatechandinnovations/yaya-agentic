import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/clone.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/tenant.dart';
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;

/// Multi-step wizard: pick destination + policies → call dry-run → review the
/// plan + warnings → apply.
///
/// The wizard is intentionally stateless about the source profile: a caller
/// supplies it and we never modify it. The destination tenant is picked from
/// the existing tenant list — registration must precede cloning per §4.
class CloneProfileWizard extends ConsumerStatefulWidget {
  const CloneProfileWizard({
    super.key,
    required this.sourceTenant,
    required this.profile,
  });
  final String sourceTenant;
  final ProfileResponse profile;

  static Future<void> show(BuildContext context,
      {required String sourceTenant, required ProfileResponse profile}) {
    return showDialog<void>(
      context: context,
      builder: (_) => Dialog(
        insetPadding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720, maxHeight: 720),
          child: CloneProfileWizard(sourceTenant: sourceTenant, profile: profile),
        ),
      ),
    );
  }

  @override
  ConsumerState<CloneProfileWizard> createState() => _CloneProfileWizardState();
}

class _CloneProfileWizardState extends ConsumerState<CloneProfileWizard> {
  int _step = 0;                                // 0 = config, 1 = plan, 2 = applied

  String? _destinationTenant;
  final _destinationProfileIdCtrl = TextEditingController();
  String _conflictPolicy = 'FAIL';
  String _knowledgeLocationStrategy = 'RETAIN';
  String _personalityPolicy = 'AUTO';

  bool _busy = false;
  ClonePlan? _plan;
  String? _appliedJobId;
  String? _stepError;                           // shown next to the action row

  @override
  void initState() {
    super.initState();
    _destinationProfileIdCtrl.text = widget.profile.id;
  }

  @override
  void dispose() {
    _destinationProfileIdCtrl.dispose();
    super.dispose();
  }

  Future<void> _runDryRun() async {
    if (_destinationTenant == null) {
      setState(() => _stepError = 'Pick a destination tenant first.');
      return;
    }
    setState(() {
      _busy = true;
      _stepError = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.cloneProfile(
        sourceTenant: widget.sourceTenant,
        profileId: widget.profile.id,
        version: widget.profile.version,
        body: CloneRequest(
          destinationTenant: _destinationTenant!,
          destinationProfileId: _destinationProfileIdCtrl.text.trim().isEmpty
              ? null
              : _destinationProfileIdCtrl.text.trim(),
          conflictPolicy: _conflictPolicy,
          knowledgeLocationStrategy: _knowledgeLocationStrategy,
          personalityPolicy: _personalityPolicy,
          dryRun: true,
        ),
      );
      setState(() {
        _plan = res.plan;
        _step = 1;
      });
    } catch (e) {
      setState(() => _stepError = formatError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _apply() async {
    setState(() {
      _busy = true;
      _stepError = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.cloneProfile(
        sourceTenant: widget.sourceTenant,
        profileId: widget.profile.id,
        version: widget.profile.version,
        body: CloneRequest(
          destinationTenant: _destinationTenant!,
          destinationProfileId: _destinationProfileIdCtrl.text.trim().isEmpty
              ? null
              : _destinationProfileIdCtrl.text.trim(),
          conflictPolicy: _conflictPolicy,
          knowledgeLocationStrategy: _knowledgeLocationStrategy,
          personalityPolicy: _personalityPolicy,
          dryRun: false,
        ),
      );
      setState(() {
        _plan = res.plan;
        _appliedJobId = res.jobId;
        _step = 2;
      });
    } catch (e) {
      setState(() => _stepError = formatError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(children: [
            const Icon(Icons.move_to_inbox_outlined),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Clone ${widget.profile.id}@${widget.profile.version} '
                'from ${widget.sourceTenant}',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            IconButton(
              onPressed: () => Navigator.of(context).pop(),
              icon: const Icon(Icons.close),
            ),
          ]),
          const SizedBox(height: 8),
          _stepIndicator(context),
          const SizedBox(height: 16),
          Expanded(child: SingleChildScrollView(child: _stepBody(context))),
          if (_stepError != null) ...[
            const SizedBox(height: 8),
            Text(_stepError!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 12),
          _actions(context),
        ],
      ),
    );
  }

  Widget _stepIndicator(BuildContext context) {
    final theme = Theme.of(context);
    Widget chip(int idx, String label) {
      final selected = _step == idx;
      final done = _step > idx;
      return Padding(
        padding: const EdgeInsets.only(right: 12),
        child: Chip(
          avatar: done
              ? Icon(Icons.check, size: 16, color: theme.colorScheme.primary)
              : Text('${idx + 1}'),
          label: Text(label),
          backgroundColor: selected
              ? theme.colorScheme.primaryContainer
              : theme.colorScheme.surface,
        ),
      );
    }

    return Row(children: [
      chip(0, 'Configure'),
      chip(1, 'Review plan'),
      chip(2, 'Applied'),
    ]);
  }

  Widget _stepBody(BuildContext context) {
    return switch (_step) {
      0 => _configBody(context),
      1 => _planBody(context),
      _ => _appliedBody(context),
    };
  }

  Widget _configBody(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    return tenants.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (list) {
        final eligible = list
            .where((t) => t.id != widget.sourceTenant && t.status != 'ARCHIVED')
            .toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              initialValue: _destinationTenant,
              decoration: const InputDecoration(
                labelText: 'destination tenant',
                helperText: 'must exist and not be archived',
              ),
              items: eligible
                  .map((t) => DropdownMenuItem(
                        value: t.id,
                        child: Text('${t.id}  (${t.status})'),
                      ))
                  .toList(),
              onChanged: (v) => setState(() => _destinationTenant = v),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _destinationProfileIdCtrl,
              decoration: const InputDecoration(
                labelText: 'destination profile id',
                helperText: 'defaults to the source profile id',
              ),
            ),
            const SizedBox(height: 16),
            _policyPicker(
              label: 'Conflict policy',
              value: _conflictPolicy,
              options: const {
                'FAIL': 'Abort if any resource id already exists at destination.',
                'SKIP': 'Reuse the destination\'s existing version.',
                'NEW_VERSION': 'Bump the destination\'s version stream.',
              },
              onChanged: (v) => setState(() => _conflictPolicy = v),
            ),
            const SizedBox(height: 12),
            _policyPicker(
              label: 'Knowledge location strategy',
              value: _knowledgeLocationStrategy,
              options: const {
                'RETAIN': 'Keep the location string verbatim. Edit before re-ingestion.',
                'TEMPLATE': 'Substitute the source tenant id → destination in location strings.',
                'OMIT': 'Clone the row but null out the location.',
              },
              onChanged: (v) => setState(() => _knowledgeLocationStrategy = v),
            ),
            const SizedBox(height: 12),
            _policyPicker(
              label: 'Personality policy',
              value: _personalityPolicy,
              options: const {
                'AUTO': 'Only clone personality for locales the destination doesn\'t already have.',
                'ALWAYS': 'Always create a new personality version at the destination.',
                'NEVER': 'Skip personality entirely; require destination to already have one.',
              },
              onChanged: (v) => setState(() => _personalityPolicy = v),
            ),
          ],
        );
      },
    );
  }

  Widget _policyPicker({
    required String label,
    required String value,
    required Map<String, String> options,
    required ValueChanged<String> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 4),
        ...options.entries.map((e) => RadioListTile<String>(
              value: e.key,
              // ignore: deprecated_member_use — RadioGroup arrives later
              groupValue: value,
              // ignore: deprecated_member_use
              onChanged: (v) {
                if (v != null) onChanged(v);
              },
              title: Text(e.key),
              subtitle: Text(e.value, style: const TextStyle(fontSize: 12)),
              contentPadding: EdgeInsets.zero,
              dense: true,
            )),
      ],
    );
  }

  Widget _planBody(BuildContext context) {
    final plan = _plan!;
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('${plan.sourceTenant} → ${plan.destinationTenant}',
            style: theme.textTheme.titleSmall),
        Text('Destination profile: ${plan.destinationProfileId}',
            style: theme.textTheme.bodySmall),
        const SizedBox(height: 12),
        if (plan.warnings.isNotEmpty)
          Card(
            color: theme.colorScheme.errorContainer.withValues(alpha: 0.3),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Warnings',
                      style: theme.textTheme.labelLarge?.copyWith(
                          color: theme.colorScheme.error)),
                  const SizedBox(height: 4),
                  ...plan.warnings.map((w) => Padding(
                        padding: const EdgeInsets.only(bottom: 2),
                        child: Text('• $w', style: const TextStyle(fontSize: 12)),
                      )),
                ],
              ),
            ),
          ),
        const SizedBox(height: 12),
        _planResourceGroup(context, 'Profile', [plan.profile]),
        _planResourceGroup(context, 'Capabilities', plan.capabilities),
        _planResourceGroup(context, 'Tools', plan.tools),
        _planKnowledgeGroup(context, plan.knowledgeSources),
        _planResourceGroup(context, 'Auth bindings', plan.authBindings),
        _planResourceGroup(context, 'Recording strategies', plan.recordingStrategies),
        _planPersonalityGroup(context, plan.personality),
      ],
    );
  }

  Widget _planResourceGroup(BuildContext context, String label,
      List<ResourceAction> items) {
    if (items.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.labelLarge),
          const SizedBox(height: 4),
          ...items.map((a) => _actionRow(theme, a.id, a.action,
              a.fromVersion, a.toVersion, a.notes)),
        ],
      ),
    );
  }

  Widget _planKnowledgeGroup(BuildContext context, List<KnowledgeAction> items) {
    if (items.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Knowledge sources', style: theme.textTheme.labelLarge),
          const SizedBox(height: 4),
          ...items.map((a) => _actionRow(theme, a.id, a.action,
              a.fromVersion, a.toVersion, a.notes)),
        ],
      ),
    );
  }

  Widget _planPersonalityGroup(BuildContext context, List<PersonalityAction> items) {
    if (items.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Personality', style: theme.textTheme.labelLarge),
          const SizedBox(height: 4),
          ...items.map((a) => _actionRow(theme, a.locale ?? '(all)',
              a.action, a.fromVersion, a.toVersion, a.notes)),
        ],
      ),
    );
  }

  Widget _actionRow(ThemeData theme, String id, String action,
      int? fromVersion, int? toVersion, List<String> notes) {
    final color = switch (action) {
      'CREATE_NEW_VERSION' || 'CREATE' => theme.colorScheme.primary,
      'REUSE_EXISTING' => theme.colorScheme.tertiary,
      'SKIP' || 'SKIP_DEFAULT_INHERITS' => theme.colorScheme.outline,
      _ => theme.colorScheme.onSurface,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Text(id, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            const SizedBox(width: 8),
            Chip(
              label: Text(action, style: TextStyle(fontSize: 10, color: color)),
              visualDensity: VisualDensity.compact,
              backgroundColor: color.withValues(alpha: 0.1),
              side: BorderSide(color: color.withValues(alpha: 0.3)),
            ),
            if (fromVersion != null && toVersion != null) ...[
              const SizedBox(width: 6),
              Text('v$fromVersion → v$toVersion',
                  style: TextStyle(fontSize: 11, color: theme.colorScheme.outline)),
            ],
          ]),
          for (final n in notes)
            Padding(
              padding: const EdgeInsets.only(left: 12),
              child: Text('· $n',
                  style: TextStyle(
                      fontSize: 11, color: theme.colorScheme.outline)),
            ),
        ],
      ),
    );
  }

  Widget _appliedBody(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Icon(Icons.check_circle, color: theme.colorScheme.primary, size: 28),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'Cloned ${widget.profile.id}@${widget.profile.version} → '
              '${_plan!.destinationTenant} as '
              '${_plan!.destinationProfileId}@v${_plan!.profile.toVersion}.',
              style: theme.textTheme.titleSmall,
            ),
          ),
        ]),
        const SizedBox(height: 12),
        if (_appliedJobId != null)
          DetailRow(label: 'audit job id', value: _appliedJobId!, mono: true),
        const SizedBox(height: 12),
        Text(
          'Tool urlTemplates were carried over as-is (paths only). The '
          'destination tenant\'s host_base_url resolves at dispatch time. '
          'Knowledge sources land UNINDEXED — operators must re-ingest under '
          'the destination tenant\'s egress/API keys.',
          style: theme.textTheme.bodySmall,
        ),
      ],
    );
  }

  Widget _actions(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        if (_step == 1)
          TextButton(
            onPressed: _busy ? null : () => setState(() {
              _step = 0;
              _plan = null;
            }),
            child: const Text('Back'),
          ),
        const SizedBox(width: 8),
        if (_step == 2)
          FilledButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Done'),
          )
        else if (_step == 1)
          FilledButton.icon(
            onPressed: _busy ? null : _apply,
            icon: _busy
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.play_arrow),
            label: const Text('Apply'),
          )
        else
          FilledButton.icon(
            onPressed: _busy ? null : _runDryRun,
            icon: _busy
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.preview),
            label: const Text('Dry-run'),
          ),
      ],
    );
  }
}

/// Convenience: a destination-tenant picker that hides the source tenant
/// and any archived tenants. Exposed standalone so other flows can reuse it.
class DestinationTenantPicker extends ConsumerWidget {
  const DestinationTenantPicker({
    super.key,
    required this.sourceTenant,
    required this.value,
    required this.onChanged,
  });
  final String sourceTenant;
  final String? value;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenants = ref.watch(tenantsProvider);
    return tenants.when(
      loading: () => const LinearProgressIndicator(),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (list) {
        final eligible = list
            .where((TenantResponse t) =>
                t.id != sourceTenant && t.status != 'ARCHIVED')
            .toList();
        return DropdownButtonFormField<String?>(
          initialValue: value,
          decoration: const InputDecoration(labelText: 'destination tenant'),
          items: eligible
              .map((t) => DropdownMenuItem<String?>(
                    value: t.id,
                    child: Text('${t.id}  (${t.status})'),
                  ))
              .toList(),
          onChanged: onChanged,
        );
      },
    );
  }
}

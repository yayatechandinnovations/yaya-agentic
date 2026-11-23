import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/clone.dart';
import '../../../models/admin/profile.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
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
          constraints: const BoxConstraints(maxWidth: 720, maxHeight: 760),
          child: CloneProfileWizard(sourceTenant: sourceTenant, profile: profile),
        ),
      ),
    );
  }

  @override
  ConsumerState<CloneProfileWizard> createState() => _CloneProfileWizardState();
}

class _CloneProfileWizardState extends ConsumerState<CloneProfileWizard> {
  int _step = 0; // 0 = configure, 1 = review plan, 2 = applied

  String? _destinationTenant;
  final _destinationProfileIdCtrl = TextEditingController();
  String _conflictPolicy = 'FAIL';
  String _knowledgeLocationStrategy = 'RETAIN';
  String _personalityPolicy = 'AUTO';

  late final YFormController _form;
  bool _busy = false;
  ClonePlan? _plan;
  String? _appliedJobId;
  String? _stepError;

  @override
  void initState() {
    super.initState();
    _destinationProfileIdCtrl.text = widget.profile.id;
    _form = YFormController(
      snapshot: () => {
        'destinationTenant': _destinationTenant,
        'destinationProfileId': _destinationProfileIdCtrl.text,
        'conflictPolicy': _conflictPolicy,
        'knowledgeLocationStrategy': _knowledgeLocationStrategy,
        'personalityPolicy': _personalityPolicy,
      },
      validate: () => {
        'destinationTenant': _destinationTenant == null ? 'Pick the tenant to clone into.' : null,
        'destinationProfileId': _destinationProfileIdCtrl.text.trim().isEmpty
            ? null
            : Validators.slugId(_destinationProfileIdCtrl.text, 'a profile ID'),
      },
      labels: const {
        'destinationTenant': 'Destination tenant',
        'destinationProfileId': 'Profile ID at the destination',
      },
    );
    _form.addListener(_onForm);
  }

  void _onForm() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_onForm);
    _form.dispose();
    _destinationProfileIdCtrl.dispose();
    super.dispose();
  }

  CloneRequest _request({required bool dryRun}) => CloneRequest(
        destinationTenant: _destinationTenant!,
        destinationProfileId: _destinationProfileIdCtrl.text.trim().isEmpty ? null : _destinationProfileIdCtrl.text.trim(),
        conflictPolicy: _conflictPolicy,
        knowledgeLocationStrategy: _knowledgeLocationStrategy,
        personalityPolicy: _personalityPolicy,
        dryRun: dryRun,
      );

  Future<void> _runDryRun() async {
    if (!_form.submit()) return;
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
        body: _request(dryRun: true),
      );
      if (!mounted) return;
      setState(() {
        _plan = res.plan;
        _step = 1;
      });
    } catch (e) {
      if (mounted) setState(() => _stepError = ApiErrorInfo.from(e).message);
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
        body: _request(dryRun: false),
      );
      if (!mounted) return;
      setState(() {
        _plan = res.plan;
        _appliedJobId = res.jobId;
        _step = 2;
      });
      showToast(context, 'Cloned ${widget.profile.id} into ${res.plan.destinationTenant}');
    } catch (e) {
      if (mounted) setState(() => _stepError = ApiErrorInfo.from(e).message);
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
              child: Text.rich(
                TextSpan(children: [
                  const TextSpan(text: 'Clone '),
                  TextSpan(text: '${widget.profile.id} v${widget.profile.version}', style: monoStyle(context, size: 16, weight: FontWeight.w600)),
                  const TextSpan(text: ' from '),
                  TextSpan(text: widget.sourceTenant, style: monoStyle(context, size: 16, weight: FontWeight.w600)),
                ]),
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            IconButton(
              tooltip: 'Close',
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
            YBanner(tone: YBannerTone.error, message: _stepError!),
          ],
          const SizedBox(height: 12),
          _actions(context),
        ],
      ),
    );
  }

  Widget _stepIndicator(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Widget chip(int idx, String label) {
      final selected = _step == idx;
      final done = _step > idx;
      return Padding(
        padding: const EdgeInsets.only(right: 12),
        child: Chip(
          avatar: done ? Icon(Icons.check, size: 16, color: scheme.primary) : Text('${idx + 1}'),
          label: Text(label),
          backgroundColor: selected ? scheme.primaryContainer : scheme.surface,
        ),
      );
    }

    return Row(children: [
      chip(0, 'Configure'),
      chip(1, 'Review plan'),
      chip(2, 'Applied'),
    ]);
  }

  Widget _stepBody(BuildContext context) => switch (_step) {
        0 => _configBody(context),
        1 => _planBody(context),
        _ => _appliedBody(context),
      };

  Widget _configBody(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    final list = tenants.valueOrNull ?? const [];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_form.summary.isNotEmpty) ...[YErrorSummary(form: _form, action: 'running the dry-run'), const SizedBox(height: Gap.fieldGap)],
        YResourcePicker(
          label: 'Destination tenant',
          form: _form,
          name: 'destinationTenant',
          required: true,
          loading: tenants.isLoading && tenants.valueOrNull == null,
          value: _destinationTenant,
          resources: [
            for (final t in list)
              if (t.id != widget.sourceTenant)
                YResource(
                  id: t.id,
                  name: t.displayName.isEmpty ? null : t.displayName,
                  meta: t.status == 'ACTIVE' ? null : t.status.toLowerCase(),
                  disabledReason: t.status == 'ARCHIVED' ? 'Archived' : null,
                ),
          ],
          helper: 'The tenant must already be registered and not archived.',
          onChanged: (v) => setState(() => _destinationTenant = v),
        ),
        if (tenants.hasError)
          YBanner(tone: YBannerTone.error, message: 'Couldn’t load tenants: ${ApiErrorInfo.from(tenants.error!).message}'),
        const SizedBox(height: Gap.fieldGap),
        YTextField(
          label: 'Profile ID at the destination',
          wireKey: 'destinationProfileId',
          controller: _destinationProfileIdCtrl,
          form: _form,
          name: 'destinationProfileId',
          optional: true,
          mono: true,
          hint: widget.profile.id,
          helper: 'Empty keeps the source profile’s ID.',
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: Gap.fieldGap),
        YEnumCards<String>(
          label: 'If a resource already exists there',
          wireKey: 'conflictPolicy',
          value: _conflictPolicy,
          options: const [
            YEnumOption('FAIL', 'Stop', wire: 'FAIL', description: 'Abort if any resource ID already exists at the destination.'),
            YEnumOption('SKIP', 'Reuse theirs', wire: 'SKIP', description: 'Keep the destination’s existing version.'),
            YEnumOption('NEW_VERSION', 'Publish a new version', wire: 'NEW_VERSION', caution: true,
                description: 'Adds a version on top of the destination’s — its new sessions pick it up.'),
          ],
          onChanged: (v) => setState(() => _conflictPolicy = v),
        ),
        const SizedBox(height: Gap.fieldGap),
        YEnumCards<String>(
          label: 'Knowledge source locations',
          wireKey: 'knowledgeLocationStrategy',
          value: _knowledgeLocationStrategy,
          options: const [
            YEnumOption('RETAIN', 'Keep as is', wire: 'RETAIN', description: 'Copy the location verbatim. Edit it before re-ingesting.'),
            YEnumOption('TEMPLATE', 'Swap the tenant ID', wire: 'TEMPLATE', description: 'Replace the source tenant ID with the destination’s in locations.'),
            YEnumOption('OMIT', 'Leave empty', wire: 'OMIT', description: 'Copy the source but clear its location.'),
          ],
          onChanged: (v) => setState(() => _knowledgeLocationStrategy = v),
        ),
        const SizedBox(height: Gap.fieldGap),
        YEnumCards<String>(
          label: 'Personality',
          wireKey: 'personalityPolicy',
          value: _personalityPolicy,
          options: const [
            YEnumOption('AUTO', 'Only what’s missing', wire: 'AUTO', description: 'Clone personality only for locales the destination doesn’t have yet.'),
            YEnumOption('ALWAYS', 'Always', wire: 'ALWAYS', caution: true, description: 'Always create a new personality version at the destination.'),
            YEnumOption('NEVER', 'Never', wire: 'NEVER', description: 'Skip it; the destination must already have one.'),
          ],
          onChanged: (v) => setState(() => _personalityPolicy = v),
        ),
      ],
    );
  }

  Widget _planBody(BuildContext context) {
    final plan = _plan!;
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text.rich(
          TextSpan(children: [
            TextSpan(text: plan.sourceTenant, style: monoStyle(context, size: 14, weight: FontWeight.w600)),
            const TextSpan(text: '  →  '),
            TextSpan(text: plan.destinationTenant, style: monoStyle(context, size: 14, weight: FontWeight.w600)),
          ]),
        ),
        const SizedBox(height: 4),
        Text.rich(TextSpan(children: [
          TextSpan(text: 'Destination profile: ', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
          TextSpan(text: plan.destinationProfileId, style: monoStyle(context, size: 13)),
        ])),
        const SizedBox(height: 12),
        if (plan.warnings.isNotEmpty) ...[
          YBanner(
            tone: YBannerTone.warning,
            title: plan.warnings.length == 1 ? '1 warning' : '${plan.warnings.length} warnings',
            message: plan.warnings.map((w) => '• $w').join('\n'),
          ),
          const SizedBox(height: 12),
        ],
        _group(context, 'Profile', [for (final a in [plan.profile]) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Capabilities', [for (final a in plan.capabilities) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Tools', [for (final a in plan.tools) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Knowledge sources', [for (final a in plan.knowledgeSources) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Auth bindings', [for (final a in plan.authBindings) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Recording strategies', [for (final a in plan.recordingStrategies) (a.id, a.action, a.fromVersion, a.toVersion, a.notes)]),
        _group(context, 'Personality', [for (final a in plan.personality) (a.locale ?? 'all locales', a.action, a.fromVersion, a.toVersion, a.notes)]),
      ],
    );
  }

  Widget _group(BuildContext context, String label, List<(String, String, int?, int?, List<String>)> items) {
    if (items.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          for (final (id, action, from, to, notes) in items) _actionRow(context, id, action, from, to, notes),
        ],
      ),
    );
  }

  Widget _actionRow(BuildContext context, String id, String action, int? fromVersion, int? toVersion, List<String> notes) {
    final scheme = Theme.of(context).colorScheme;
    final tone = switch (action) {
      'CREATE_NEW_VERSION' || 'CREATE' => YTone.brand,
      'REUSE_EXISTING' => YTone.info,
      'SKIP' || 'SKIP_DEFAULT_INHERITS' => YTone.neutral,
      _ => YTone.neutral,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
            Text(id, style: monoStyle(context, size: 13)),
            YBadge(action, tone: tone, mono: true),
            if (fromVersion != null && toVersion != null)
              Text('v$fromVersion → v$toVersion', style: monoStyle(context, size: 12, color: scheme.onSurfaceVariant)),
          ]),
          for (final n in notes)
            Padding(
              padding: const EdgeInsets.only(left: 12, top: 2),
              child: Text('· $n', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
            ),
        ],
      ),
    );
  }

  Widget _appliedBody(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final plan = _plan!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(children: [
          Icon(Icons.check_circle, color: StatusColors.of(context).success, size: 28),
          const SizedBox(width: 12),
          Expanded(
            child: Text.rich(
              TextSpan(children: [
                const TextSpan(text: 'Cloned into '),
                TextSpan(text: plan.destinationTenant, style: monoStyle(context, size: 15, weight: FontWeight.w600)),
                const TextSpan(text: ' as '),
                TextSpan(text: '${plan.destinationProfileId} v${plan.profile.toVersion ?? '?'}', style: monoStyle(context, size: 15, weight: FontWeight.w600)),
                const TextSpan(text: '.'),
              ]),
              style: Theme.of(context).textTheme.titleSmall,
            ),
          ),
        ]),
        if (_appliedJobId != null) ...[
          const SizedBox(height: 12),
          Row(children: [
            Text('Audit job ', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
            Flexible(child: SelectableText(_appliedJobId!, style: monoStyle(context, size: 13))),
          ]),
        ],
        const SizedBox(height: 12),
        const YBanner(
          tone: YBannerTone.info,
          message: 'Tool request paths were copied as they are; the destination tenant’s base URL applies when they run. '
              'Knowledge sources arrive unindexed — re-ingest them under the destination tenant’s keys.',
        ),
      ],
    );
  }

  Widget _actions(BuildContext context) {
    Widget busy(IconData icon) => _busy
        ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
        : Icon(icon, size: 18);
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        if (_step == 0)
          OutlinedButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        if (_step == 1)
          OutlinedButton(
            onPressed: _busy
                ? null
                : () => setState(() {
                      _step = 0;
                      _plan = null;
                    }),
            child: const Text('Back'),
          ),
        const SizedBox(width: 8),
        if (_step == 2)
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Done'))
        else if (_step == 1)
          FilledButton.icon(
            onPressed: _busy ? null : _apply,
            icon: busy(Icons.play_arrow),
            label: Text('Clone into ${_destinationTenant ?? 'tenant'}'),
          )
        else
          FilledButton.icon(
            onPressed: _busy ? null : _runDryRun,
            icon: busy(Icons.preview),
            label: const Text('Preview the plan'),
          ),
      ],
    );
  }
}

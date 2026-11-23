import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/sms_channel.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import '../channel_bindings/sender_routes_section.dart';
import '../shared/admin_shared.dart' show TenantScopedEmptyState;

/// SMS A2P 10DLC admin: set up the carrier brand, register campaigns (use
/// cases), then provision senders. Twilio / TCR are invisible — the operator
/// describes a business + use case and the platform registers on their behalf.
///
/// US senders gate on an APPROVED campaign; DR senders on a regulatory bundle.

const _pendingBrand = {'SUBMITTING', 'PENDING_VERIFICATION', 'PENDING_REVIEW', 'IN_PROGRESS'};
const _pendingCampaign = {'SUBMITTING', 'PENDING_REVIEW', 'IN_PROGRESS'};
const _pendingSender = {'SUBMITTING', 'PENDING_REVIEW'};

// Carrier verdicts arrive by callback or by the backend's own poll, never as
// an admin write — so each list re-asks while something is waiting on one.
final smsBrandProvider = FutureProvider.family<SmsBrandResponse?, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.sms);
  final api = await ref.watch(adminApiProvider.future);
  final brand = await api.getSmsBrand(tenantId);
  pollWhile(ref, brand != null && _pendingBrand.contains(brand.status), every: const Duration(seconds: 10));
  return brand;
});

final smsCampaignsProvider = FutureProvider.family<List<SmsCampaignResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.sms);
  final api = await ref.watch(adminApiProvider.future);
  final list = await api.listSmsCampaigns(tenantId);
  pollWhile(ref, list.any((c) => _pendingCampaign.contains(c.status)), every: const Duration(seconds: 10));
  return list;
});

final smsSendersProvider = FutureProvider.family<List<SmsSenderResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.sms);
  final api = await ref.watch(adminApiProvider.future);
  final list = await api.listSmsSenders(tenantId);
  pollWhile(ref, list.any((s) => _pendingSender.contains(s.status)), every: const Duration(seconds: 5));
  return list;
});

const _useCases = <String, (String, String)>{
  'ACCOUNT_NOTIFICATION': ('Account notifications', 'Password resets, account changes, security alerts.'),
  'CUSTOMER_CARE': ('Customer care', 'Support conversations the customer started.'),
  'DELIVERY_NOTIFICATION': ('Delivery notifications', 'Order and shipping updates.'),
  'TWO_FACTOR_AUTH': ('Two-factor codes', 'One-time passcodes for sign-in.'),
  'MARKETING': ('Marketing', 'Promotions and offers. Needs explicit opt-in.'),
  'POLLING_VOTING': ('Polling and voting', 'Surveys and votes.'),
  'PUBLIC_SERVICE_ANNOUNCEMENT': ('Public service announcements', 'Information for the public good.'),
  'GAMBLING': ('Gambling', 'Restricted: extra carrier approval and licensing evidence.'),
  'SWEEPSTAKES': ('Sweepstakes', 'Restricted: extra carrier approval and evidence.'),
  'HIGH_RISK_FINANCIAL': ('High-risk financial', 'Restricted: extra carrier approval and evidence.'),
};

String _useCaseLabel(String code) => _useCases[code]?.$1 ?? humanizeWire(code);

String _marketLabel(String m) => switch (m) {
      'US' => 'United States',
      'DO' => 'Dominican Republic',
      _ => m,
    };

YBadge _statusBadge(String status) => YBadge(humanizeWire(status), tone: toneForStatus(status), icon: iconForStatus(status));

class SmsAdminScreen extends ConsumerWidget {
  const SmsAdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'SMS senders');
    return _SmsPage(key: ValueKey(tenant), tenant: tenant);
  }
}

class _SmsPage extends ConsumerStatefulWidget {
  const _SmsPage({super.key, required this.tenant});
  final String tenant;

  @override
  ConsumerState<_SmsPage> createState() => _SmsPageState();
}

const _brandLabels = {
  'legalName': 'Legal business name',
  'taxId': 'Tax ID (EIN)',
  'address': 'Address',
  'vertical': 'Industry',
  'websiteUrl': 'Website',
  'contactEmail': 'Contact email',
};

class _SmsPageState extends ConsumerState<_SmsPage> {
  final _legal = TextEditingController();
  final _ein = TextEditingController();
  final _address = TextEditingController();
  final _vertical = TextEditingController();
  final _website = TextEditingController();
  final _email = TextEditingController();
  late final YFormController _brandForm;

  /// Which server copy the form was loaded from, so a refetch only resets
  /// the form when the brand really changed and nobody is typing.
  String? _loadedFrom;
  bool _syncScheduled = false;

  String get _tenant => widget.tenant;

  @override
  void initState() {
    super.initState();
    _brandForm = YFormController(
      snapshot: () => {
        'legalName': _legal.text.trim(),
        'taxId': _ein.text.trim(),
        'address': _address.text.trim(),
        'vertical': _vertical.text.trim(),
        'websiteUrl': _website.text.trim(),
        'contactEmail': _email.text.trim(),
      },
      validate: () => {
        'legalName': Validators.required(_legal.text, 'the legal business name'),
        'taxId': Validators.required(_ein.text, 'the tax ID (EIN)'),
        'websiteUrl': Validators.httpUrl(_website.text, optional: true),
        'contactEmail': ChannelValidators.email(_email.text, optional: true),
      },
      labels: _brandLabels,
      draftKey: 'yaya.draft.smsBrand.$_tenant.brand',
    );
    _brandForm.addListener(_rebuild);
    _brandForm.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      String s(String k, TextEditingController c) => (v[k] as String?) ?? c.text;
      _legal.text = s('legalName', _legal);
      _ein.text = s('taxId', _ein);
      _address.text = s('address', _address);
      _vertical.text = s('vertical', _vertical);
      _website.text = s('websiteUrl', _website);
      _email.text = s('contactEmail', _email);
      _brandForm.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _brandForm.removeListener(_rebuild);
    _brandForm.dispose();
    for (final c in [_legal, _ein, _address, _vertical, _website, _email]) {
      c.dispose();
    }
    super.dispose();
  }

  void _syncBrand(SmsBrandResponse? b) {
    final key = b == null ? 'none' : '${b.id}:${b.updatedAt}:${b.status}';
    if (key == _loadedFrom || _brandForm.isDirty) return;
    _loadedFrom = key;
    _legal.text = b?.business.legalName ?? '';
    _ein.text = b?.business.taxId ?? '';
    _address.text = b?.business.address ?? '';
    _vertical.text = b?.business.vertical ?? '';
    _website.text = b?.business.websiteUrl ?? '';
    _email.text = b?.business.contactEmail ?? '';
    _brandForm.markClean();
  }

  String? _opt(TextEditingController c) => c.text.trim().isEmpty ? null : c.text.trim();

  Future<void> _saveBrand(SmsBrandResponse? existing) async {
    if (!_brandForm.submit()) return;
    _brandForm.saving = true;
    final req = SmsBrandRequest(SmsBusinessIdentity(
      legalName: _legal.text.trim(),
      taxId: _ein.text.trim(),
      address: _opt(_address),
      vertical: _opt(_vertical),
      websiteUrl: _opt(_website),
      contactEmail: _opt(_email),
    ));
    try {
      final api = await ref.read(adminApiProvider.future);
      existing == null ? await api.createSmsBrand(_tenant, req) : await api.updateSmsBrand(_tenant, req);
      _brandForm.saving = false;
      _brandForm.markClean();
      _loadedFrom = null;
      ref.invalidate(smsBrandProvider(_tenant));
      if (mounted) showToast(context, existing == null ? 'Created the brand ${req.business.legalName}' : 'Saved the brand');
    } catch (e) {
      _brandForm.saving = false;
      _brandForm.applyServerError(e);
    }
  }

  Future<void> _submitBrand(SmsBrandResponse b) async {
    final ok = await confirmAction(
      context,
      title: 'Submit ${b.business.legalName} for carrier verification?',
      message: 'The brand is locked while carriers verify it — usually minutes, sometimes days. If they reject it, '
          'you can fix the details and submit again.',
      confirmLabel: 'Submit brand',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.submitSmsBrand(_tenant);
      ref.invalidate(smsBrandProvider(_tenant));
      if (mounted) showToast(context, 'Submitted ${b.business.legalName} for verification');
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final brandAsync = ref.watch(smsBrandProvider(_tenant));
    final brand = brandAsync.valueOrNull;
    // Filling the form notifies its fields, which can't happen mid-build.
    if (brandAsync.hasValue && !_syncScheduled && !_brandForm.isDirty) {
      _syncScheduled = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _syncScheduled = false;
        if (mounted) _syncBrand(ref.read(smsBrandProvider(_tenant)).valueOrNull);
      });
    }
    final editable = brandAsync.hasValue && (brand == null || brand.isEditable);

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Expanded(
        child: YPage(
          title: 'SMS (A2P 10DLC)',
          subtitle: 'Text customers from US and Dominican numbers. US carriers need three things, in order: your '
              'business (brand), what you’ll send (campaign), and the numbers (senders).',
          scrollable: true,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            _brandSection(brandAsync, editable),
            const SizedBox(height: Gap.lg),
            _CampaignsSection(tenant: _tenant, brand: brand),
            const SizedBox(height: Gap.lg),
            _SendersSection(tenant: _tenant),
          ]),
        ),
      ),
      if (editable)
        YUnsavedGuard(
          form: _brandForm,
          child: YActionBar(
            form: _brandForm,
            maxWidth: 1280,
            primaryLabel: brand == null ? 'Create brand' : 'Save brand',
            onPrimary: () => _saveBrand(brand),
            onCancel: _brandForm.isDirty
                ? () {
                    _loadedFrom = null;
                    _brandForm.clearDraft();
                    _brandForm.markClean();
                    _syncBrand(brand);
                  }
                : null,
            cancelLabel: 'Discard changes',
            secondary: [
              if (brand != null)
                Tooltip(
                  message: _brandForm.isDirty ? 'Save your changes first' : 'Send the brand to the carriers',
                  child: OutlinedButton.icon(
                    onPressed: _brandForm.isDirty || _brandForm.saving ? null : () => _submitBrand(brand),
                    icon: const Icon(Icons.send_outlined, size: 18),
                    label: const Text('Submit for verification'),
                  ),
                ),
            ],
          ),
        ),
    ]);
  }

  Widget _brandSection(AsyncValue<SmsBrandResponse?> brandAsync, bool editable) {
    final brand = brandAsync.valueOrNull;
    if (!brandAsync.hasValue) {
      return YFormSection(
        title: '1. Business (brand)',
        children: [
          if (brandAsync.hasError)
            YBanner(
              tone: YBannerTone.error,
              title: 'Couldn’t load the brand.',
              message: ApiErrorInfo.from(brandAsync.error!).message,
              actions: [TextButton(onPressed: () => ref.invalidate(smsBrandProvider(_tenant)), child: const Text('Retry'))],
            )
          else
            const LinearProgressIndicator(),
        ],
      );
    }
    final children = <Widget>[
      if (_brandForm.summary.isNotEmpty || _brandForm.formError != null)
        YErrorSummary(form: _brandForm, action: brand == null ? 'creating the brand' : 'saving'),
      if (brand?.failureMessage != null)
        YBanner(tone: YBannerTone.error, title: 'Carriers rejected the brand.', message: '${brand!.failureMessage} Fix the details and submit again.'),
      if (brand != null && _pendingBrand.contains(brand.status))
        const YBanner(tone: YBannerTone.info, message: 'Carriers are verifying the brand. This page updates by itself when they answer.'),
    ];
    if (editable) {
      children.addAll([
        YTextField(
          label: 'Legal business name',
          wireKey: 'business.legalName',
          controller: _legal,
          form: _brandForm,
          name: 'legalName',
          required: true,
          hint: 'e.g. Acme Foods LLC',
          helper: 'Exactly as registered with the IRS.',
        ),
        YTextField(
          label: 'Tax ID (EIN)',
          wireKey: 'business.taxId',
          controller: _ein,
          form: _brandForm,
          name: 'taxId',
          required: true,
          mono: true,
          hint: 'e.g. 12-3456789',
          helper: 'The employer identification number carriers check the legal name against.',
        ),
        YTextField(
          label: 'Address',
          wireKey: 'business.address',
          controller: _address,
          form: _brandForm,
          name: 'address',
          optional: true,
          maxLines: 2,
          minLines: 1,
        ),
        YTextField(
          label: 'Industry',
          wireKey: 'business.vertical',
          controller: _vertical,
          form: _brandForm,
          name: 'vertical',
          optional: true,
          mono: true,
          hint: 'e.g. RETAIL',
          helper: 'The carrier industry code, if you know it.',
        ),
        YTextField(
          label: 'Website',
          wireKey: 'business.websiteUrl',
          controller: _website,
          form: _brandForm,
          name: 'websiteUrl',
          optional: true,
          keyboardType: TextInputType.url,
          hint: 'e.g. https://acme.example',
        ),
        YTextField(
          label: 'Contact email',
          wireKey: 'business.contactEmail',
          controller: _email,
          form: _brandForm,
          name: 'contactEmail',
          optional: true,
          keyboardType: TextInputType.emailAddress,
          hint: 'e.g. compliance@acme.example',
        ),
      ]);
    } else {
      final b = brand!.business;
      children.add(Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        ChannelDetailRow(label: 'Legal business name', value: b.legalName),
        ChannelDetailRow(label: 'Tax ID (EIN)', value: b.taxId, mono: true),
        if (b.address != null) ChannelDetailRow(label: 'Address', value: b.address!),
        if (b.vertical != null) ChannelDetailRow(label: 'Industry', value: b.vertical!, mono: true),
        if (b.websiteUrl != null) ChannelDetailRow(label: 'Website', value: b.websiteUrl!),
        if (b.contactEmail != null) ChannelDetailRow(label: 'Contact email', value: b.contactEmail!),
        if (brand.lastSyncAt != null) ChannelDetailRow(label: 'Last checked', value: formatRelativeIso(brand.lastSyncAt)),
      ]));
    }
    return YFormSection(
      title: '1. Business (brand)',
      description: brand == null
          ? 'Register your business identity with the carriers. Save it, then submit it for verification.'
          : editable
              ? 'Edit the details, save, then submit for verification.'
              : 'Locked while carriers verify or after approval.',
      trailing: brand == null ? const YBadge('Not set up') : _statusBadge(brand.status),
      children: children,
    );
  }
}

/// A table inside a scrolling page needs a height; size it to the rows.
double _tableHeight(int rows) => (rows * 62 + 150).clamp(240, 560).toDouble();

class _CampaignsSection extends ConsumerWidget {
  const _CampaignsSection({required this.tenant, required this.brand});
  final String tenant;
  final SmsBrandResponse? brand;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final campaigns = ref.watch(smsCampaignsProvider(tenant));
    Future<void> create() => showYSheet<void>(
          context,
          width: 640,
          label: 'New campaign',
          builder: (_) => _CampaignEditorSheet(tenant: tenant, brandId: brand!.id),
        );
    final newButton = Tooltip(
      message: brand == null ? 'Create the brand first' : 'Register a new use case',
      child: FilledButton.icon(
        onPressed: brand == null ? null : create,
        icon: const Icon(Icons.add, size: 18),
        label: const Text('New campaign'),
      ),
    );
    final count = campaigns.valueOrNull?.length ?? 0;
    return YFormSection(
      title: '2. Campaigns (use cases)',
      description: 'What you’ll send and how people opt in. A US number can only send once its campaign is approved.',
      trailing: newButton,
      children: [
        SizedBox(
          height: count == 0 ? 360 : _tableHeight(count),
          child: LiveAsyncView<List<SmsCampaignResponse>>(
            value: campaigns,
            what: 'campaigns',
            onRetry: () => ref.invalidate(smsCampaignsProvider(tenant)),
            data: (context, list) => YTable<SmsCampaignResponse>(
              rows: list,
              noun: 'campaigns',
              minWidth: 640,
              idOf: (c) => c.id,
              searchHint: 'Search campaigns',
              searchTextOf: (c) => '${_useCaseLabel(c.useCase)} ${c.description} ${c.status}',
              onRowTap: (c) => showYSheet<void>(
                context,
                width: 600,
                label: 'Campaign',
                builder: (_) => _CampaignSheet(tenant: tenant, campaignId: c.id),
              ),
              filters: [
                YFilter('Approved', (c) => c.status == 'APPROVED'),
                YFilter('Waiting', (c) => _pendingCampaign.contains(c.status) || c.requiresOperatorReview),
                YFilter('Drafts', (c) => c.status == 'DRAFT'),
              ],
              empty: YEmptyState(
                icon: Icons.campaign_outlined,
                title: 'No campaigns yet',
                message: brand == null
                    ? 'Set up the brand above first. A campaign then describes what you’ll text people.'
                    : 'A campaign tells carriers what you’ll send — “delivery updates”, “one-time codes” — with samples.',
                action: brand == null ? null : FilledButton.icon(onPressed: create, icon: const Icon(Icons.add, size: 18), label: const Text('Create a campaign')),
              ),
              columns: [
                YColumn(
                  label: 'Use case',
                  flex: 3,
                  sortKey: (c) => _useCaseLabel(c.useCase),
                  cell: (context, c) => YCellStack(primary: _useCaseLabel(c.useCase), secondary: c.description),
                ),
                YColumn(
                  label: 'Status',
                  flex: 2,
                  sortKey: (c) => c.status,
                  cell: (context, c) => Wrap(spacing: 6, runSpacing: 4, children: [
                    _statusBadge(c.status),
                    if (c.restricted) const YBadge('Restricted', tone: YTone.warning, icon: Icons.gpp_maybe_outlined),
                    if (c.requiresOperatorReview) const YBadge('Operator review', tone: YTone.info),
                  ]),
                ),
                YColumn(
                  label: 'Created',
                  width: 120,
                  sortKey: (c) => c.createdAt ?? '',
                  cell: (context, c) => Text(formatRelativeIso(c.createdAt)),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _CampaignSheet extends ConsumerStatefulWidget {
  const _CampaignSheet({required this.tenant, required this.campaignId});
  final String tenant;
  final String campaignId;

  @override
  ConsumerState<_CampaignSheet> createState() => _CampaignSheetState();
}

class _CampaignSheetState extends ConsumerState<_CampaignSheet> {
  bool _busy = false;

  Future<void> _submit(SmsCampaignResponse c) async {
    final ok = await confirmAction(
      context,
      title: 'Submit “${_useCaseLabel(c.useCase)}” to the carriers?',
      message: c.restricted
          ? 'This is a restricted use case: a platform operator reviews it before carriers see it, and some carriers '
              'refuse it outright. It can’t be edited after submitting.'
          : 'Carriers review the description and samples — usually a few days. It can’t be edited after submitting.',
      confirmLabel: 'Submit campaign',
    );
    if (!ok) return;
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.submitSmsCampaign(widget.tenant, c.id);
      ref.invalidate(smsCampaignsProvider(widget.tenant));
      if (mounted) showToast(context, 'Submitted “${_useCaseLabel(c.useCase)}”');
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = (ref.watch(smsCampaignsProvider(widget.tenant)).valueOrNull ?? const <SmsCampaignResponse>[])
        .where((c) => c.id == widget.campaignId)
        .firstOrNull;
    if (c == null) {
      return const YSheetScaffold(title: 'Campaign', body: YEmptyState(title: 'This campaign is gone', icon: Icons.search_off));
    }
    final scheme = Theme.of(context).colorScheme;
    return YSheetScaffold(
      title: _useCaseLabel(c.useCase),
      subtitle: c.id,
      body: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 28, 28), children: [
        Wrap(spacing: 6, runSpacing: 6, children: [
          _statusBadge(c.status),
          if (c.restricted) const YBadge('Restricted', tone: YTone.warning, icon: Icons.gpp_maybe_outlined),
          if (c.requiresOperatorReview) const YBadge('Awaiting operator review', tone: YTone.info),
        ]),
        if (c.failureMessage != null) ...[
          const SizedBox(height: Gap.md),
          YBanner(tone: YBannerTone.error, title: 'Carriers rejected it.', message: c.failureMessage!),
        ],
        const ChannelSheetHeading('Details'),
        ChannelDetailRow(label: 'Use case', value: c.useCase, mono: true),
        ChannelDetailRow(label: 'Description', value: c.description),
        ChannelDetailRow(label: 'How people opt in', value: c.optIn.description),
        if (c.optIn.proofUrl != null) ChannelDetailRow(label: 'Opt-in proof', value: c.optIn.proofUrl!),
        if (c.createdAt != null) ChannelDetailRow(label: 'Created', value: formatRelativeIso(c.createdAt)),
        const ChannelSheetHeading('Sample messages'),
        for (final s in c.sampleMessages)
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: scheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(10)),
            child: SelectableText(s, style: TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface)),
          ),
      ]),
      footer: c.status != 'DRAFT'
          ? null
          : _SheetFooter(children: [
              FilledButton.icon(
                onPressed: _busy ? null : () => _submit(c),
                icon: _busy
                    ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.send_outlined, size: 18),
                label: const Text('Submit to carriers'),
              ),
            ]),
    );
  }
}

class _SheetFooter extends StatelessWidget {
  const _SheetFooter({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(color: scheme.surface, border: Border(top: BorderSide(color: scheme.outlineVariant))),
      padding: const EdgeInsets.symmetric(horizontal: Gap.pagePadding, vertical: 12),
      child: Wrap(alignment: WrapAlignment.end, spacing: 8, runSpacing: 8, children: children),
    );
  }
}

const _campaignLabels = {
  'useCase': 'Use case',
  'description': 'Description',
  'samples': 'Sample messages',
  'optIn': 'How people opt in',
};

class _CampaignEditorSheet extends ConsumerStatefulWidget {
  const _CampaignEditorSheet({required this.tenant, required this.brandId});
  final String tenant;
  final String brandId;

  @override
  ConsumerState<_CampaignEditorSheet> createState() => _CampaignEditorSheetState();
}

class _CampaignEditorSheetState extends ConsumerState<_CampaignEditorSheet> {
  String _useCase = 'CUSTOMER_CARE';
  final _description = TextEditingController();
  final _optIn = TextEditingController();
  final List<TextEditingController> _samples = [TextEditingController(), TextEditingController()];
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {
        'useCase': _useCase,
        'description': _description.text.trim(),
        'samples': [for (final s in _samples) s.text.trim()],
        'optIn': _optIn.text.trim(),
      },
      validate: () {
        final filled = _samples.where((s) => s.text.trim().isNotEmpty).length;
        return {
          'description': Validators.required(_description.text, 'what these messages are for'),
          'samples': filled < 2 ? 'Carriers need at least two sample messages.' : null,
          'optIn': Validators.required(_optIn.text, 'how people agree to get these messages'),
        };
      },
      labels: _campaignLabels,
      draftKey: 'yaya.draft.smsCampaign.${widget.tenant}.new',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _useCase = (v['useCase'] as String?) ?? _useCase;
      _description.text = (v['description'] as String?) ?? '';
      _optIn.text = (v['optIn'] as String?) ?? '';
      final samples = (v['samples'] as List?)?.map((e) => '$e').toList() ?? const [];
      while (_samples.length < samples.length && _samples.length < 5) {
        _samples.add(TextEditingController());
      }
      for (var i = 0; i < samples.length && i < _samples.length; i++) {
        _samples[i].text = samples[i];
      }
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_description, _optIn, ..._samples]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createSmsCampaign(
          widget.tenant,
          SmsCampaignRequest(
            brandId: widget.brandId,
            useCase: _useCase,
            description: _description.text.trim(),
            sampleMessages: _samples.map((s) => s.text.trim()).where((s) => s.isNotEmpty).toList(),
            optIn: SmsOptIn(description: _optIn.text.trim()),
          ));
      ref.invalidate(smsCampaignsProvider(widget.tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Created the “${_useCaseLabel(_useCase)}” campaign. Submit it when ready.');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final restricted = const {'GAMBLING', 'SWEEPSTAKES', 'HIGH_RISK_FINANCIAL'}.contains(_useCase);
    final fields = <Widget>[
      if (_form.summary.isNotEmpty || _form.formError != null) YErrorSummary(form: _form, action: 'creating the campaign'),
      YSelectField<String>(
        label: 'Use case',
        wireKey: 'useCase',
        form: _form,
        name: 'useCase',
        required: true,
        value: _useCase,
        options: [for (final e in _useCases.entries) YOption(e.key, e.value.$1, meta: e.value.$2)],
        helper: _useCases[_useCase]?.$2,
        onChanged: (v) => setState(() => _useCase = v ?? _useCase),
      ),
      if (restricted)
        const YBanner(
          tone: YBannerTone.warning,
          title: 'Restricted use case.',
          message: 'A platform operator reviews it before it reaches carriers, licensing evidence is required, and '
              'some carriers refuse it outright.',
        ),
      YTextField(
        label: 'Description',
        wireKey: 'description',
        controller: _description,
        form: _form,
        name: 'description',
        required: true,
        minLines: 2,
        maxLines: 4,
        hint: 'e.g. Order confirmations and delivery updates for customers of Acme Foods.',
        helper: 'What these messages are and who gets them.',
      ),
      YFieldShell(
        label: 'Sample messages',
        form: _form,
        name: 'samples',
        required: true,
        wireKey: 'sampleMessages',
        helper: 'Two to five real examples, including your business name and opt-out wording (“Reply STOP to opt out”).',
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (var i = 0; i < _samples.length; i++)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  child: TextField(
                    controller: _samples[i],
                    minLines: 1,
                    maxLines: 3,
                    style: const TextStyle(fontSize: 14),
                    decoration: InputDecoration(hintText: 'Sample ${i + 1}'),
                    onChanged: (_) => _form.changed('samples'),
                  ),
                ),
                if (_samples.length > 2)
                  IconButton(
                    tooltip: 'Remove sample ${i + 1}',
                    icon: const Icon(Icons.close),
                    onPressed: () => setState(() {
                      _samples.removeAt(i).dispose();
                      _form.changed('samples');
                    }),
                  ),
              ]),
            ),
          if (_samples.length < 5)
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => setState(() => _samples.add(TextEditingController())),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('Add a sample'),
              ),
            ),
        ]),
      ),
      YTextField(
        label: 'How people opt in',
        wireKey: 'optIn.description',
        controller: _optIn,
        form: _form,
        name: 'optIn',
        required: true,
        minLines: 2,
        maxLines: 4,
        hint: 'e.g. Customers tick “Text me updates” at checkout on acme.example.',
        helper: 'Where and how a person agrees to receive these texts. Carriers reject vague answers.',
      ),
    ];
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: 'New campaign',
          subtitle: 'Saved as a draft; submit it to the carriers from the list.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [for (var i = 0; i < fields.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), fields[i]]],
          ),
          footer: YActionBar(form: _form, primaryLabel: 'Create campaign', onPrimary: _save, onCancel: () => closeYSheet(context)),
        ),
      ),
    );
  }
}

class _SendersSection extends ConsumerWidget {
  const _SendersSection({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final senders = ref.watch(smsSendersProvider(tenant));
    final campaigns = {for (final c in ref.watch(smsCampaignsProvider(tenant)).valueOrNull ?? const <SmsCampaignResponse>[]) c.id: c};
    Future<void> create() => showYSheet<void>(
          context,
          width: 600,
          label: 'New SMS sender',
          builder: (_) => _SenderEditorSheet(tenant: tenant),
        );
    final count = senders.valueOrNull?.length ?? 0;
    return YFormSection(
      title: '3. Senders (numbers)',
      description: 'The numbers you text from. US numbers attach to an approved campaign; Dominican numbers need a '
          'regulatory bundle before they can be submitted.',
      trailing: FilledButton.icon(onPressed: create, icon: const Icon(Icons.add, size: 18), label: const Text('New sender')),
      children: [
        SizedBox(
          height: count == 0 ? 360 : _tableHeight(count),
          child: LiveAsyncView<List<SmsSenderResponse>>(
            value: senders,
            what: 'SMS senders',
            onRetry: () => ref.invalidate(smsSendersProvider(tenant)),
            data: (context, list) => YTable<SmsSenderResponse>(
              rows: list,
              noun: 'senders',
              minWidth: 640,
              idOf: (s) => s.id,
              searchHint: 'Search by number',
              searchTextOf: (s) => '${s.phoneE164} ${s.market} ${s.status}',
              onRowTap: (s) => showYSheet<void>(
                context,
                width: 640,
                label: 'SMS sender',
                builder: (_) => _SmsSenderSheet(tenant: tenant, senderId: s.id),
              ),
              filters: [
                YFilter('Active', (s) => s.status == 'ACTIVE'),
                YFilter('Waiting', (s) => _pendingSender.contains(s.status)),
                YFilter('Needs action', (s) => s.status == 'DRAFT' || s.status == 'FAILED'),
              ],
              empty: YEmptyState(
                icon: Icons.sms_outlined,
                title: 'No SMS senders yet',
                message: 'A sender is a phone number customers get your texts from and can reply to.',
                action: FilledButton.icon(onPressed: create, icon: const Icon(Icons.add, size: 18), label: const Text('Add a sender')),
              ),
              columns: [
                YColumn(
                  label: 'Number',
                  flex: 2,
                  sortKey: (s) => s.phoneE164,
                  cell: (context, s) => Text(s.phoneE164, style: monoStyle(context, size: 13, weight: FontWeight.w500)),
                ),
                YColumn(
                  label: 'Market',
                  flex: 2,
                  sortKey: (s) => s.market,
                  cell: (context, s) => YCellStack(primary: _marketLabel(s.market), secondary: humanizeWire(s.senderKind)),
                ),
                YColumn(
                  label: 'Compliance',
                  flex: 2,
                  cell: (context, s) => Text(
                    s.market == 'US'
                        ? (campaigns[s.campaignId] == null ? 'No campaign' : _useCaseLabel(campaigns[s.campaignId]!.useCase))
                        : (s.hasRegulatoryBundle ? 'Bundle attached' : 'Bundle needed'),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                YColumn(
                  label: 'Status',
                  flex: 2,
                  sortKey: (s) => s.status,
                  cell: (context, s) => _statusBadge(s.status),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _SmsSenderSheet extends ConsumerStatefulWidget {
  const _SmsSenderSheet({required this.tenant, required this.senderId});
  final String tenant;
  final String senderId;

  @override
  ConsumerState<_SmsSenderSheet> createState() => _SmsSenderSheetState();
}

class _SmsSenderSheetState extends ConsumerState<_SmsSenderSheet> {
  bool _busy = false;

  Future<void> _run(Future<void> Function(AdminApi api) call, String ok) async {
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await call(api);
      ref.invalidate(smsSendersProvider(widget.tenant));
      if (mounted) showToast(context, ok);
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = (ref.watch(smsSendersProvider(widget.tenant)).valueOrNull ?? const <SmsSenderResponse>[])
        .where((s) => s.id == widget.senderId)
        .firstOrNull;
    if (s == null) {
      return const YSheetScaffold(title: 'SMS sender', body: YEmptyState(title: 'This sender is gone', icon: Icons.search_off));
    }
    final campaign = (ref.watch(smsCampaignsProvider(widget.tenant)).valueOrNull ?? const <SmsCampaignResponse>[])
        .where((c) => c.id == s.campaignId)
        .firstOrNull;
    final busyIcon = _busy ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2)) : null;
    final canSubmit = s.status == 'DRAFT' || s.status == 'FAILED';
    final settling = _pendingSender.contains(s.status);
    return YSheetScaffold(
      title: s.phoneE164,
      subtitle: '${_marketLabel(s.market)} · ${humanizeWire(s.status)}',
      body: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 28, 28), children: [
        Align(alignment: Alignment.centerLeft, child: _statusBadge(s.status)),
        if (s.onboarding.failureMessage != null) ...[
          const SizedBox(height: Gap.md),
          YBanner(tone: YBannerTone.error, title: 'The number wasn’t accepted.', message: s.onboarding.failureMessage!),
        ],
        if (settling) ...[
          const SizedBox(height: Gap.md),
          const YBanner(tone: YBannerTone.info, message: 'Registration is in progress. This updates by itself when the carrier answers.'),
        ],
        if (s.market == 'US' && campaign != null && !campaign.isApproved && canSubmit) ...[
          const SizedBox(height: Gap.md),
          YBanner(
            tone: YBannerTone.warning,
            message: 'Its campaign “${_useCaseLabel(campaign.useCase)}” isn’t approved yet, so submitting will be refused.',
          ),
        ],
        if (s.market == 'DO' && !s.hasRegulatoryBundle && canSubmit) ...[
          const SizedBox(height: Gap.md),
          const YBanner(
            tone: YBannerTone.warning,
            message: 'Dominican numbers need a regulatory bundle before they can be submitted. Ask your platform '
                'administrator to attach one.',
          ),
        ],
        const ChannelSheetHeading('Details'),
        ChannelDetailRow(label: 'Number', value: s.phoneE164, mono: true),
        ChannelDetailRow(label: 'Market', value: _marketLabel(s.market)),
        ChannelDetailRow(label: 'Kind', value: humanizeWire(s.senderKind)),
        ChannelDetailRow(label: 'Acquired by', value: humanizeWire(s.acquisition)),
        if (s.market == 'US')
          ChannelDetailRow(label: 'Campaign', value: campaign == null ? (s.campaignId ?? 'None') : _useCaseLabel(campaign.useCase)),
        if (s.market != 'US') ChannelDetailRow(label: 'Regulatory bundle', value: s.hasRegulatoryBundle ? 'Attached' : 'Not attached'),
        ChannelDetailRow(label: 'Default locale', value: s.defaultLocale, mono: true),
        if (s.lastSyncAt != null) ChannelDetailRow(label: 'Last checked', value: formatRelativeIso(s.lastSyncAt)),
        ChannelDetailRow(label: 'Sender ID', value: s.id, mono: true),
        if (s.status != 'REVOKED') ...[
          const SizedBox(height: Gap.sm),
          SenderRoutesSection(endpointKind: 'SMS_SENDER', endpointId: s.id, supportsResolver: false),
        ],
      ]),
      footer: !canSubmit && !settling
          ? null
          : _SheetFooter(children: [
              if (settling)
                OutlinedButton.icon(
                  onPressed: _busy ? null : () => _run((api) => api.syncSmsSender(widget.tenant, s.id), 'Status refreshed'),
                  icon: busyIcon ?? const Icon(Icons.refresh, size: 18),
                  label: const Text('Refresh status'),
                ),
              if (canSubmit)
                FilledButton.icon(
                  onPressed: _busy ? null : () => _run((api) => api.submitSmsSender(widget.tenant, s.id), 'Submitted ${s.phoneE164}'),
                  icon: busyIcon ?? const Icon(Icons.send_outlined, size: 18),
                  label: Text(s.status == 'FAILED' ? 'Submit again' : 'Submit for registration'),
                ),
            ]),
    );
  }
}

const _senderLabels = {'phoneE164': 'Phone number', 'market': 'Market', 'campaignId': 'Campaign'};

class _SenderEditorSheet extends ConsumerStatefulWidget {
  const _SenderEditorSheet({required this.tenant});
  final String tenant;

  @override
  ConsumerState<_SenderEditorSheet> createState() => _SenderEditorSheetState();
}

class _SenderEditorSheetState extends ConsumerState<_SenderEditorSheet> {
  final _phone = TextEditingController();
  String _market = 'US';
  String? _campaignId;
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    final campaigns = ref.read(smsCampaignsProvider(widget.tenant)).valueOrNull ?? const <SmsCampaignResponse>[];
    _campaignId = (campaigns.where((c) => c.isApproved).firstOrNull ?? campaigns.firstOrNull)?.id;
    _form = YFormController(
      snapshot: () => {'phoneE164': _phone.text.trim(), 'market': _market, 'campaignId': _campaignId},
      validate: () => {
        'phoneE164': ChannelValidators.e164(_phone.text),
        'campaignId': _market == 'US' && _campaignId == null ? 'US numbers must attach to a campaign. Create one first.' : null,
      },
      labels: _senderLabels,
    );
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _phone.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.createSmsSender(
        widget.tenant,
        SmsSenderRequest(phoneE164: _phone.text.trim(), market: _market, campaignId: _market == 'US' ? _campaignId : null),
      );
      ref.invalidate(smsSendersProvider(widget.tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Added ${saved.phoneE164}. Submit it for registration when ready.');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final campaigns = ref.watch(smsCampaignsProvider(widget.tenant));
    final fields = <Widget>[
      if (_form.summary.isNotEmpty || _form.formError != null) YErrorSummary(form: _form, action: 'adding the sender'),
      YTextField(
        label: 'Phone number',
        wireKey: 'phoneE164',
        controller: _phone,
        form: _form,
        name: 'phoneE164',
        required: true,
        autofocus: true,
        mono: true,
        keyboardType: const TextInputType.numberWithOptions(signed: true),
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+]'))],
        hint: 'e.g. +15551234567',
        helper: 'International format: + then the country code and number.',
      ),
      YEnumCards<String>(
        label: 'Market',
        wireKey: 'market',
        form: _form,
        name: 'market',
        required: true,
        horizontal: true,
        value: _market,
        onChanged: (v) => setState(() => _market = v),
        options: const [
          YEnumOption('US', 'United States', wire: 'US', description: 'A2P 10DLC — attaches to an approved campaign.'),
          YEnumOption('DO', 'Dominican Republic',
              wire: 'DO', description: 'Needs a regulatory bundle. Carriers may overwrite the sender ID.'),
        ],
      ),
      if (_market == 'US')
        YResourcePicker(
          label: 'Campaign',
          wireKey: 'campaignId',
          form: _form,
          name: 'campaignId',
          required: true,
          value: _campaignId,
          loading: campaigns.isLoading,
          resources: [
            for (final c in campaigns.valueOrNull ?? const <SmsCampaignResponse>[])
              YResource(id: c.id, name: _useCaseLabel(c.useCase), meta: humanizeWire(c.status)),
          ],
          helper: 'Only an approved campaign lets the number be submitted.',
          onChanged: (v) => setState(() => _campaignId = v),
        ),
    ];
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: 'New SMS sender',
          subtitle: 'Saved as a draft; submit it for registration from its details.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [for (var i = 0; i < fields.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), fields[i]]],
          ),
          footer: YActionBar(form: _form, primaryLabel: 'Add sender', onPrimary: _save, onCancel: () => closeYSheet(context)),
        ),
      ),
    );
  }
}

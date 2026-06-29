import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/sms_channel.dart';
import '../shared/admin_shared.dart';

/// SMS A2P 10DLC admin: set up the carrier Brand, register Campaigns (use
/// cases), then provision senders. Twilio / TCR are invisible — the operator
/// describes a business + use case and the platform registers on their behalf.
///
/// US senders gate on an APPROVED campaign; DR senders on a regulatory bundle.

final smsBrandProvider =
    FutureProvider.family<SmsBrandResponse?, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.getSmsBrand(tenantId);
});

final smsCampaignsProvider =
    FutureProvider.family<List<SmsCampaignResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listSmsCampaigns(tenantId);
});

final smsSendersProvider =
    FutureProvider.family<List<SmsSenderResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listSmsSenders(tenantId);
});

const _useCases = <String>[
  'ACCOUNT_NOTIFICATION',
  'CUSTOMER_CARE',
  'DELIVERY_NOTIFICATION',
  'TWO_FACTOR_AUTH',
  'MARKETING',
  'POLLING_VOTING',
  'PUBLIC_SERVICE_ANNOUNCEMENT',
  'GAMBLING',
  'SWEEPSTAKES',
  'HIGH_RISK_FINANCIAL',
];

class SmsAdminScreen extends ConsumerWidget {
  const SmsAdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    return Scaffold(
      appBar: AppBar(title: const Text('SMS (A2P 10DLC)')),
      body: tenant == null
          ? const Center(child: Text('Select a tenant to manage SMS.'))
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _BrandSection(tenant: tenant),
                const SizedBox(height: 16),
                _CampaignsSection(tenant: tenant),
                const SizedBox(height: 16),
                _SendersSection(tenant: tenant),
              ],
            ),
    );
  }
}

Widget _statusChip(BuildContext context, String status) {
  Color c;
  switch (status) {
    case 'VERIFIED':
    case 'APPROVED':
    case 'ACTIVE':
      c = Colors.green;
      break;
    case 'FAILED':
      c = Theme.of(context).colorScheme.error;
      break;
    case 'PENDING_VERIFICATION':
    case 'PENDING_REVIEW':
    case 'SUBMITTING':
      c = Colors.orange;
      break;
    default:
      c = Colors.blueGrey;
  }
  return Chip(
    label: Text(status, style: const TextStyle(fontSize: 12)),
    backgroundColor: c.withValues(alpha: 0.15),
    side: BorderSide(color: c.withValues(alpha: 0.5)),
    visualDensity: VisualDensity.compact,
  );
}

Future<void> _runWrite(BuildContext context, WidgetRef ref, String tenant,
    Future<void> Function(AdminApi api) call, String okMsg) async {
  try {
    final api = await ref.read(adminApiProvider.future);
    await call(api);
    ref.invalidate(smsBrandProvider(tenant));
    ref.invalidate(smsCampaignsProvider(tenant));
    ref.invalidate(smsSendersProvider(tenant));
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(okMsg)));
    }
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(formatError(e)), backgroundColor: Theme.of(context).colorScheme.error));
    }
  }
}

// ---------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------

class _BrandSection extends ConsumerWidget {
  const _BrandSection({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final brand = ref.watch(smsBrandProvider(tenant));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              const Text('1. Business (Brand)', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              const Spacer(),
              brand.maybeWhen(
                  data: (b) => b == null ? const SizedBox() : _statusChip(context, b.status),
                  orElse: () => const SizedBox()),
            ]),
            const SizedBox(height: 8),
            brand.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(formatError(e)),
              data: (b) => b == null
                  ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Text('Register your business identity with the carriers to send US SMS.'),
                      const SizedBox(height: 8),
                      FilledButton.icon(
                        icon: const Icon(Icons.add_business),
                        label: const Text('Create brand'),
                        onPressed: () => _brandDialog(context, ref, tenant, null),
                      ),
                    ])
                  : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(b.business.legalName, style: const TextStyle(fontWeight: FontWeight.w600)),
                      Text('EIN ${b.business.taxId}', style: const TextStyle(color: Colors.grey)),
                      if (b.failureMessage != null)
                        Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Text(b.failureMessage!,
                                style: TextStyle(color: Theme.of(context).colorScheme.error))),
                      const SizedBox(height: 8),
                      Wrap(spacing: 8, children: [
                        if (b.isEditable)
                          OutlinedButton(
                              onPressed: () => _brandDialog(context, ref, tenant, b),
                              child: const Text('Edit')),
                        if (b.isEditable)
                          FilledButton(
                              onPressed: () => _runWrite(context, ref, tenant,
                                  (api) => api.submitSmsBrand(tenant).then((_) {}), 'Brand submitted'),
                              child: const Text('Submit for verification')),
                      ]),
                    ]),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _brandDialog(
      BuildContext context, WidgetRef ref, String tenant, SmsBrandResponse? existing) async {
    final legal = TextEditingController(text: existing?.business.legalName ?? '');
    final ein = TextEditingController(text: existing?.business.taxId ?? '');
    final addr = TextEditingController(text: existing?.business.address ?? '');
    final vertical = TextEditingController(text: existing?.business.vertical ?? '');
    final site = TextEditingController(text: existing?.business.websiteUrl ?? '');
    final email = TextEditingController(text: existing?.business.contactEmail ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(existing == null ? 'Create brand' : 'Edit brand'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            _field(legal, 'Legal business name *'),
            _field(ein, 'Tax ID / EIN *'),
            _field(addr, 'Address'),
            _field(vertical, 'Vertical (e.g. RETAIL)'),
            _field(site, 'Website URL'),
            _field(email, 'Contact email'),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
        ],
      ),
    );
    if (ok != true) return;
    final req = SmsBrandRequest(SmsBusinessIdentity(
      legalName: legal.text.trim(),
      taxId: ein.text.trim(),
      address: addr.text.trim().isEmpty ? null : addr.text.trim(),
      vertical: vertical.text.trim().isEmpty ? null : vertical.text.trim(),
      websiteUrl: site.text.trim().isEmpty ? null : site.text.trim(),
      contactEmail: email.text.trim().isEmpty ? null : email.text.trim(),
    ));
    await _runWrite(
        context,
        ref,
        tenant,
        (api) => (existing == null ? api.createSmsBrand(tenant, req) : api.updateSmsBrand(tenant, req))
            .then((_) {}),
        existing == null ? 'Brand created' : 'Brand updated');
  }
}

// ---------------------------------------------------------------------------
// Campaigns
// ---------------------------------------------------------------------------

class _CampaignsSection extends ConsumerWidget {
  const _CampaignsSection({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final campaigns = ref.watch(smsCampaignsProvider(tenant));
    final brand = ref.watch(smsBrandProvider(tenant)).valueOrNull;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Text('2. Campaigns (use cases)',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const Spacer(),
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New campaign'),
              onPressed: brand == null
                  ? null
                  : () => _campaignDialog(context, ref, tenant, brand.id),
            ),
          ]),
          if (brand == null)
            const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('Create the brand first.', style: TextStyle(color: Colors.grey))),
          const SizedBox(height: 8),
          campaigns.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e)),
            data: (list) => list.isEmpty
                ? const Text('No campaigns yet.', style: TextStyle(color: Colors.grey))
                : Column(
                    children: list
                        .map((c) => ListTile(
                              contentPadding: EdgeInsets.zero,
                              title: Row(children: [
                                Text(c.useCase),
                                if (c.restricted)
                                  const Padding(
                                      padding: EdgeInsets.only(left: 6),
                                      child: Icon(Icons.gpp_maybe, size: 16, color: Colors.orange)),
                              ]),
                              subtitle: Text(
                                  '${c.description}${c.requiresOperatorReview ? ' • awaiting operator review' : ''}'
                                  '${c.failureMessage != null ? '\n${c.failureMessage}' : ''}'),
                              trailing: Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                                _statusChip(context, c.status),
                                if (c.status == 'DRAFT')
                                  TextButton(
                                      onPressed: () => _runWrite(context, ref, tenant,
                                          (api) => api.submitSmsCampaign(tenant, c.id).then((_) {}),
                                          'Campaign submitted'),
                                      child: const Text('Submit')),
                              ]),
                            ))
                        .toList(),
                  ),
          ),
        ]),
      ),
    );
  }

  Future<void> _campaignDialog(BuildContext context, WidgetRef ref, String tenant, String brandId) async {
    String useCase = _useCases.first;
    final desc = TextEditingController();
    final s1 = TextEditingController();
    final s2 = TextEditingController();
    final optIn = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('New campaign'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              DropdownButtonFormField<String>(
                initialValue: useCase,
                decoration: const InputDecoration(labelText: 'Use case'),
                items: _useCases.map((u) => DropdownMenuItem(value: u, child: Text(u))).toList(),
                onChanged: (v) => setState(() => useCase = v ?? useCase),
              ),
              _field(desc, 'Description *'),
              _field(s1, 'Sample message 1 *'),
              _field(s2, 'Sample message 2 *'),
              _field(optIn, 'Opt-in description *'),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final req = SmsCampaignRequest(
      brandId: brandId,
      useCase: useCase,
      description: desc.text.trim(),
      sampleMessages: [s1.text.trim(), s2.text.trim()].where((s) => s.isNotEmpty).toList(),
      optIn: SmsOptIn(description: optIn.text.trim()),
    );
    await _runWrite(context, ref, tenant,
        (api) => api.createSmsCampaign(tenant, req).then((_) {}), 'Campaign created');
  }
}

// ---------------------------------------------------------------------------
// Senders
// ---------------------------------------------------------------------------

class _SendersSection extends ConsumerWidget {
  const _SendersSection({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final senders = ref.watch(smsSendersProvider(tenant));
    final campaigns = ref.watch(smsCampaignsProvider(tenant)).valueOrNull ?? const [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Text('3. Senders (numbers)',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const Spacer(),
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New sender'),
              onPressed: () => _senderDialog(context, ref, tenant, campaigns),
            ),
          ]),
          const SizedBox(height: 8),
          senders.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e)),
            data: (list) => list.isEmpty
                ? const Text('No senders yet.', style: TextStyle(color: Colors.grey))
                : Column(
                    children: list
                        .map((s) => ListTile(
                              contentPadding: EdgeInsets.zero,
                              title: Text('${s.phoneE164}  (${s.market})'),
                              subtitle: Text(
                                  '${s.senderKind} • ${s.acquisition}'
                                  '${s.onboarding.failureMessage != null ? '\n${s.onboarding.failureMessage}' : ''}'),
                              trailing: Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                                _statusChip(context, s.status),
                                if (s.status == 'DRAFT' || s.status == 'FAILED')
                                  TextButton(
                                      onPressed: () => _runWrite(context, ref, tenant,
                                          (api) => api.submitSmsSender(tenant, s.id).then((_) {}),
                                          'Sender submitted'),
                                      child: const Text('Submit')),
                                if (s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW')
                                  TextButton(
                                      onPressed: () => _runWrite(context, ref, tenant,
                                          (api) => api.syncSmsSender(tenant, s.id).then((_) {}),
                                          'Status refreshed'),
                                      child: const Text('Sync')),
                              ]),
                            ))
                        .toList(),
                  ),
          ),
        ]),
      ),
    );
  }

  Future<void> _senderDialog(BuildContext context, WidgetRef ref, String tenant,
      List<SmsCampaignResponse> campaigns) async {
    final phone = TextEditingController();
    String market = 'US';
    String? campaignId = campaigns.isNotEmpty ? campaigns.first.id : null;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('New sender'),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            _field(phone, 'Phone (E.164, e.g. +15551234567) *'),
            DropdownButtonFormField<String>(
              initialValue: market,
              decoration: const InputDecoration(labelText: 'Market'),
              items: const [
                DropdownMenuItem(value: 'US', child: Text('United States')),
                DropdownMenuItem(value: 'DO', child: Text('Dominican Republic')),
              ],
              onChanged: (v) => setState(() => market = v ?? 'US'),
            ),
            if (market == 'US')
              DropdownButtonFormField<String>(
                initialValue: campaignId,
                decoration: const InputDecoration(labelText: 'Campaign'),
                items: campaigns
                    .map((c) => DropdownMenuItem(value: c.id, child: Text('${c.useCase} (${c.status})')))
                    .toList(),
                onChanged: (v) => setState(() => campaignId = v),
              ),
            if (market == 'DO')
              const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                      'Note: DR operators overwrite the sender ID. A regulatory bundle is required before submit.',
                      style: TextStyle(color: Colors.orange, fontSize: 12))),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final req = SmsSenderRequest(
      phoneE164: phone.text.trim(),
      market: market,
      campaignId: market == 'US' ? campaignId : null,
    );
    await _runWrite(context, ref, tenant,
        (api) => api.createSmsSender(tenant, req).then((_) {}), 'Sender created');
  }
}

Widget _field(TextEditingController c, String label) => Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: TextField(controller: c, decoration: InputDecoration(labelText: label)),
    );

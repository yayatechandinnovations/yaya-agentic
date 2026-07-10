import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/context/org_context_bloc.dart';
import '../../../../core/theme/app_theme.dart';
import '../../domain/entities/organization.dart';
import '../bloc/orgs_bloc.dart';

/// Organizations — rich cards with the business profile, plus create and edit.
/// The route provides the [OrgsBloc]; this widget only reads state and
/// dispatches events.
class OrgsPage extends StatelessWidget {
  const OrgsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Organizations')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptForm(context),
        icon: const Icon(Icons.add),
        label: const Text('New organization'),
      ),
      body: BlocConsumer<OrgsBloc, OrgsState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(state.message!)));
          // Keep the console-wide org picker in sync with creates/renames.
          context.read<OrgContextBloc>().add(const OrgContextRequested());
        },
        builder: (context, state) {
          switch (state.status) {
            case OrgsStatus.initial:
            case OrgsStatus.loading:
              return const Center(child: CircularProgressIndicator());
            case OrgsStatus.failure:
              return Center(
                child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed to load')),
              );
            case OrgsStatus.loaded:
              if (state.orgs.isEmpty) {
                return const Center(child: Text('No organizations yet — create the first one.'));
              }
              return ListView.builder(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 88),
                itemCount: state.orgs.length,
                itemBuilder: (_, i) => _OrgCard(org: state.orgs[i]),
              );
          }
        },
      ),
    );
  }
}

class _OrgCard extends StatelessWidget {
  const _OrgCard({required this.org});

  final Organization org;

  @override
  Widget build(BuildContext context) {
    final details = <(IconData, String)>[
      if ((org.description ?? '').isNotEmpty) (Icons.notes, org.description!),
      if ((org.contactEmail ?? '').isNotEmpty) (Icons.mail_outline, org.contactEmail!),
      if ((org.contactPhone ?? '').isNotEmpty) (Icons.phone_outlined, org.contactPhone!),
      if ((org.website ?? '').isNotEmpty) (Icons.language, org.website!),
      if ((org.country ?? '').isNotEmpty) (Icons.public, org.country!),
    ];
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    gradient: AppBrand.gradient,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.apartment, color: Colors.white, size: 22),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(org.name,
                          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                      Text(org.slug,
                          style: const TextStyle(fontSize: 12, color: Colors.black54)),
                    ],
                  ),
                ),
                Wrap(
                  spacing: 6,
                  children: [
                    Chip(
                      avatar: Icon(Icons.people, size: 14, color: Colors.black.withValues(alpha: 0.6)),
                      label: Text('${org.memberCount} member${org.memberCount == 1 ? '' : 's'}'),
                      visualDensity: VisualDensity.compact,
                    ),
                    if (org.mfaRequired)
                      const Chip(
                        avatar: Icon(Icons.shield, size: 14, color: AppBrand.violet),
                        label: Text('MFA'),
                        visualDensity: VisualDensity.compact,
                      ),
                    if (org.status != 'ACTIVE')
                      Chip(
                        label: Text(org.status),
                        visualDensity: VisualDensity.compact,
                        side: const BorderSide(color: Colors.orange),
                      ),
                  ],
                ),
                const SizedBox(width: 4),
                IconButton(
                  tooltip: 'Edit organization',
                  icon: const Icon(Icons.edit_outlined, size: 20),
                  onPressed: () => _promptForm(context, existing: org),
                ),
              ],
            ),
            if (details.isNotEmpty) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 20,
                runSpacing: 6,
                children: details
                    .map((d) => Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(d.$1, size: 15, color: Colors.black45),
                            const SizedBox(width: 5),
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 380),
                              child: Text(d.$2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontSize: 13, color: Colors.black87)),
                            ),
                          ],
                        ))
                    .toList(),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Create ([existing] == null) or edit an organization with the full business
/// profile, not just a name.
Future<void> _promptForm(BuildContext context, {Organization? existing}) async {
  final bloc = context.read<OrgsBloc>();
  final name = TextEditingController(text: existing?.name ?? '');
  final description = TextEditingController(text: existing?.description ?? '');
  final email = TextEditingController(text: existing?.contactEmail ?? '');
  final phone = TextEditingController(text: existing?.contactPhone ?? '');
  final website = TextEditingController(text: existing?.website ?? '');
  final country = TextEditingController(text: existing?.country ?? '');
  final formKey = GlobalKey<FormState>();

  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(existing == null ? 'New organization' : 'Edit ${existing.name}'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: name,
                  autofocus: existing == null,
                  decoration: const InputDecoration(labelText: 'Name *'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Name is required' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: description,
                  decoration: const InputDecoration(
                    labelText: 'Description',
                    hintText: 'What this organization does',
                  ),
                  maxLines: 2,
                ),
                const SizedBox(height: 12),
                Row(children: [
                  Expanded(
                    child: TextFormField(
                      controller: email,
                      decoration: const InputDecoration(labelText: 'Contact email'),
                      validator: (v) => (v != null && v.trim().isNotEmpty && !v.contains('@'))
                          ? 'Not a valid email'
                          : null,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextFormField(
                      controller: phone,
                      decoration: const InputDecoration(
                          labelText: 'Contact phone', hintText: '+1809…'),
                    ),
                  ),
                ]),
                const SizedBox(height: 12),
                Row(children: [
                  Expanded(
                    child: TextFormField(
                      controller: website,
                      decoration: const InputDecoration(
                          labelText: 'Website', hintText: 'https://…'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextFormField(
                      controller: country,
                      decoration: const InputDecoration(
                          labelText: 'Country', hintText: 'DO, US, …'),
                    ),
                  ),
                ]),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            if (formKey.currentState?.validate() ?? false) Navigator.pop(ctx, true);
          },
          child: Text(existing == null ? 'Create' : 'Save'),
        ),
      ],
    ),
  );

  if (ok == true) {
    String? blankToNull(TextEditingController c) =>
        c.text.trim().isEmpty ? null : c.text.trim();
    final draft = OrgDraft(
      name: name.text.trim(),
      description: blankToNull(description),
      contactEmail: blankToNull(email),
      contactPhone: blankToNull(phone),
      website: blankToNull(website),
      country: blankToNull(country),
    );
    bloc.add(existing == null
        ? OrgCreateRequested(draft)
        : OrgUpdateRequested(existing.id, draft));
  }
}

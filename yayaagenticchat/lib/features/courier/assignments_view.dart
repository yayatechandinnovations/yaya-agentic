import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../models/commerce/money.dart';
import 'courier_models.dart';
import 'courier_providers.dart';

/// The approved courier's working surface (yaya-sales-design.md §10.4): the
/// assignment list, accept, per-assignment status advance with proof, and the
/// COD collection action.
class AssignmentsView extends ConsumerWidget {
  const AssignmentsView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(courierProvider);
    final assignments = state.assignments;

    return RefreshIndicator(
      onRefresh: () => ref.read(courierProvider.notifier).refreshAssignments(),
      child: assignments.isEmpty
          ? ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(24),
              children: const [
                SizedBox(height: 48),
                Icon(Icons.pedal_bike, size: 48),
                SizedBox(height: 12),
                Text('No deliveries assigned right now.\nPull to refresh.',
                    textAlign: TextAlign.center),
              ],
            )
          : ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(12),
              itemCount: assignments.length,
              itemBuilder: (context, i) => _AssignmentCard(assignment: assignments[i]),
            ),
    );
  }
}

class _AssignmentCard extends ConsumerWidget {
  const _AssignmentCard({required this.assignment});

  final CourierAssignment assignment;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier = ref.read(courierProvider.notifier);
    final busy = ref.watch(courierProvider.select((s) => s.busy));
    final shipment = assignment.shipment;
    final deliveryStatus = shipment?.deliveryStatus ?? '';
    final delivered = deliveryStatus == 'DELIVERED' || deliveryStatus == 'FAILED';

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    shipment == null ? 'Delivery' : 'Order #${shipment.shortOrderId}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                _StatusChip(label: deliveryStatus.isEmpty ? assignment.status : deliveryStatus),
              ],
            ),
            if (assignment.shipToLine.isNotEmpty) ...[
              const SizedBox(height: 6),
              Row(children: [
                const Icon(Icons.place_outlined, size: 16),
                const SizedBox(width: 4),
                Expanded(child: Text(assignment.shipToLine)),
              ]),
            ],
            if (assignment.codDue) ...[
              const SizedBox(height: 6),
              Row(children: [
                const Icon(Icons.payments_outlined, size: 16),
                const SizedBox(width: 4),
                Text('Collect ${_codDue(assignment)} in cash on delivery',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
              ]),
            ],
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (!assignment.accepted && assignment.status == 'ASSIGNED')
                  FilledButton.icon(
                    onPressed: busy
                        ? null
                        : () => notifier.acceptAssignment(assignment.assignmentId),
                    icon: const Icon(Icons.check),
                    label: const Text('Accept'),
                  ),
                if (assignment.accepted && !delivered && deliveryStatus != 'OUT_FOR_DELIVERY')
                  FilledButton.tonalIcon(
                    onPressed: busy
                        ? null
                        : () =>
                            notifier.advance(assignment.assignmentId, 'OUT_FOR_DELIVERY'),
                    icon: const Icon(Icons.local_shipping_outlined),
                    label: const Text('Start delivery'),
                  ),
                if (assignment.accepted && deliveryStatus == 'OUT_FOR_DELIVERY') ...[
                  FilledButton.icon(
                    onPressed: busy
                        ? null
                        : () => _proofDialog(context, ref, 'DELIVERED',
                            title: 'Mark delivered'),
                    icon: const Icon(Icons.task_alt),
                    label: const Text('Delivered'),
                  ),
                  OutlinedButton.icon(
                    onPressed: busy
                        ? null
                        : () => _proofDialog(context, ref, 'FAILED',
                            title: 'Delivery failed'),
                    icon: const Icon(Icons.error_outline),
                    label: const Text('Failed'),
                  ),
                ],
                if (assignment.accepted && assignment.codDue && !delivered)
                  FilledButton.tonalIcon(
                    onPressed: busy ? null : () => _collectCodDialog(context, ref),
                    icon: const Icon(Icons.attach_money),
                    label: Text('Collect ${_codDue(assignment)}'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _codDue(CourierAssignment a) =>
      // The courier list payload carries only the minor amount; currency rides
      // on the collect response. Render with the store's default look (USD-style
      // grouping) — the confirm dialog restates the exact number.
      Money(a.codDueMinor ?? 0, 'USD').format();

  /// v1 proof of delivery: notes and/or the recipient-spoken OTP (photo capture
  /// needs a picker dependency the app doesn't carry yet).
  Future<void> _proofDialog(BuildContext context, WidgetRef ref, String status,
      {required String title}) async {
    final notes = TextEditingController();
    final otp = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: notes,
              decoration: InputDecoration(
                labelText: status == 'FAILED' ? 'What happened?' : 'Notes (optional)',
                border: const OutlineInputBorder(),
              ),
              maxLines: 2,
            ),
            if (status == 'DELIVERED') ...[
              const SizedBox(height: 12),
              TextField(
                controller: otp,
                decoration: const InputDecoration(
                  labelText: 'Recipient code (if provided)',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(title)),
        ],
      ),
    );
    if (confirmed == true) {
      await ref
          .read(courierProvider.notifier)
          .advance(assignment.assignmentId, status, notes: notes.text, otp: otp.text);
    }
    notes.dispose();
    otp.dispose();
  }

  /// COD confirm: the amount is fixed by the tender — the courier confirms the
  /// exact number, never types a different one (§8.3: no under/over-collect).
  Future<void> _collectCodDialog(BuildContext context, WidgetRef ref) async {
    final due = assignment.codDueMinor ?? 0;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Collect cash on delivery'),
        content: Text('Confirm you collected exactly ${_codDue(assignment)} '
            'from the customer. This marks the order as paid.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text('Collected ${_codDue(assignment)}')),
        ],
      ),
    );
    if (confirmed == true) {
      await ref.read(courierProvider.notifier).collectCod(assignment.assignmentId, due);
    }
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        label.replaceAll('_', ' ').toLowerCase(),
        style: TextStyle(fontSize: 12, color: scheme.onSecondaryContainer),
      ),
    );
  }
}

/// "just now", "5 min ago", "Yesterday", "Nov 18".
String formatRelative(DateTime? t, {DateTime? now}) {
  if (t == null) return '—';
  final n = now ?? DateTime.now();
  final local = t.toLocal();
  final d = n.difference(local);
  if (d.inSeconds < 45) return 'Just now';
  if (d.inMinutes < 60) return '${d.inMinutes} min ago';
  if (d.inHours < 24 && local.day == n.day) return '${d.inHours} h ago';
  final yesterday = n.subtract(const Duration(days: 1));
  if (local.year == yesterday.year && local.month == yesterday.month && local.day == yesterday.day) return 'Yesterday';
  if (d.inDays < 7) return '${d.inDays} days ago';
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  final m = months[local.month - 1];
  return local.year == n.year ? '$m ${local.day}' : '$m ${local.day}, ${local.year}';
}

String formatRelativeIso(String? iso, {DateTime? now}) =>
    formatRelative(iso == null ? null : DateTime.tryParse(iso), now: now);

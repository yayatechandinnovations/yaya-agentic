/// The persistent owner menu delivered over the `menu` UiHint
/// (yaya-owner-ai-experience-v2-design.md §3). Session-scoped chrome, not a
/// bubble: the controller keeps only the LATEST menu and replaces it whenever
/// a new `menu` hint arrives.
///
/// Wire shape:
/// {"sections":[{"title":"Pendientes","items":[
///   {"id":"menu.undo_requests","label":"Solicitudes de anulación",
///    "badge":3,"send":"Ver solicitudes de anulación pendientes"}]}]}
class ChatMenu {
  final List<ChatMenuSection> sections;

  const ChatMenu({this.sections = const []});

  bool get isEmpty => sections.every((s) => s.items.isEmpty);

  /// Sum of all item badges — drives the aggregate dot on the docked button.
  int get totalBadge => sections.fold(
      0, (sum, s) => sum + s.items.fold(0, (n, i) => n + (i.badge ?? 0)));

  factory ChatMenu.fromJson(Map<String, dynamic> j) => ChatMenu(
        sections: [
          for (final s in (j['sections'] as List? ?? const []))
            if (s is Map)
              ChatMenuSection.fromJson(Map<String, dynamic>.from(s)),
        ],
      );
}

class ChatMenuSection {
  final String title;
  final List<ChatMenuItem> items;

  const ChatMenuSection({required this.title, this.items = const []});

  factory ChatMenuSection.fromJson(Map<String, dynamic> j) => ChatMenuSection(
        title: (j['title'] ?? '').toString(),
        items: [
          for (final i in (j['items'] as List? ?? const []))
            if (i is Map) ChatMenuItem.fromJson(Map<String, dynamic>.from(i)),
        ],
      );
}

class ChatMenuItem {
  final String id;
  final String label;
  final int? badge;

  /// The user message sent when the item is tapped — same path as a quick reply.
  final String send;

  const ChatMenuItem({
    required this.id,
    required this.label,
    this.badge,
    required this.send,
  });

  factory ChatMenuItem.fromJson(Map<String, dynamic> j) => ChatMenuItem(
        id: (j['id'] ?? '').toString(),
        label: (j['label'] ?? '').toString(),
        badge: (j['badge'] as num?)?.toInt(),
        send: (j['send'] ?? j['label'] ?? '').toString(),
      );
}

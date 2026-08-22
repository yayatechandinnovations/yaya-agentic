import 'commerce_json.dart';

/// The `confirm` UiHint payload — the two-phase guardrail before a money-mover
/// (checkout) runs. The client shows `summary` with Confirm/Cancel; approval is
/// a normal text reply the engine's `ConfirmDetector` reads as affirmative
/// ("yes") — there is no dedicated confirm endpoint (design §5.3, §9).
class ConfirmPrompt {
  final String toolId;
  final String? callId;
  final Map<String, dynamic> args;
  final String summary;

  /// Multi-item confirm (owner-ai-experience v2 §4): when present the card
  /// renders a checkbox list and the approval carries a structured
  /// `{"kind":"confirm_items","itemIds":[…]}` selection. Absent → classic
  /// single confirm, unchanged.
  final List<ConfirmItem> items;

  /// e.g. "Total a revertir: RD$4,300" — shown under the item list.
  final String? totalLine;

  /// Label for the approve button, e.g. "SÍ, aprobar 3". Fallback: "SÍ".
  final String? confirmLabel;

  const ConfirmPrompt({
    required this.toolId,
    this.callId,
    this.args = const {},
    required this.summary,
    this.items = const [],
    this.totalLine,
    this.confirmLabel,
  });

  bool get hasItems => items.isNotEmpty;

  factory ConfirmPrompt.fromJson(Map<String, dynamic> j) => ConfirmPrompt(
        toolId: (j['toolId'] ?? '').toString(),
        callId: asStr(j['callId']),
        args: asMap(j['args']),
        summary: (j['summary'] ?? '').toString(),
        items: [
          for (final e in (j['items'] as List? ?? const []))
            if (e is Map) ConfirmItem.fromJson(Map<String, dynamic>.from(e)),
        ],
        totalLine: asStr(j['totalLine']),
        confirmLabel: asStr(j['confirmLabel']),
      );
}

/// One selectable row of a multi-item confirm:
/// {"id":"UR-1","label":"UR-1 · A91-228 · B-02 · RD$1,500","checked":true}
class ConfirmItem {
  final String id;
  final String label;
  final bool checked;

  const ConfirmItem({required this.id, required this.label, this.checked = true});

  factory ConfirmItem.fromJson(Map<String, dynamic> j) => ConfirmItem(
        id: (j['id'] ?? '').toString(),
        label: (j['label'] ?? '').toString(),
        checked: (j['checked'] ?? true) == true,
      );
}

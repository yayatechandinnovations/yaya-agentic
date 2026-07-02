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

  const ConfirmPrompt({
    required this.toolId,
    this.callId,
    this.args = const {},
    required this.summary,
  });

  factory ConfirmPrompt.fromJson(Map<String, dynamic> j) => ConfirmPrompt(
        toolId: (j['toolId'] ?? '').toString(),
        callId: asStr(j['callId']),
        args: asMap(j['args']),
        summary: (j['summary'] ?? '').toString(),
      );
}

import 'package:equatable/equatable.dart';

/// A PDP decision as the console sees it. On a DENY both reasons are present and
/// distinct (#3) — the console shows the user-safe reason prominently and the
/// audit reason in a separate, operator-only panel.
class Decision extends Equatable {
  const Decision({
    required this.permit,
    this.userSafeReason,
    this.auditReason,
  });

  final bool permit;
  final String? userSafeReason;
  final String? auditReason;

  @override
  List<Object?> get props => [permit, userSafeReason, auditReason];
}

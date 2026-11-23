import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../ui/data/y_badge.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/tokens.dart';

/// Small pieces the Channels screens (WhatsApp, SMS, System inbound, Network)
/// share. Kept here rather than in `lib/ui/` because they encode channel rules
/// (E.164, BCP-47) and provider status vocabularies.

/// The current route's query (`?id=`, `?new=1`), or empty when the screen is
/// hosted outside a GoRouter (widget tests, nested navigators).
Map<String, String> routeQuery(BuildContext context) {
  if (GoRouter.maybeOf(context) == null) return const {};
  try {
    return GoRouterState.of(context).uri.queryParameters;
  } catch (_) {
    return const {};
  }
}

/// Drops a handled deep link so a reload doesn't reopen the editor.
void clearRouteQuery(BuildContext context, String path) {
  if (GoRouter.maybeOf(context) == null) return;
  context.go(path);
}

abstract final class ChannelValidators {
  static final _e164 = RegExp(r'^\+[1-9][0-9]{6,17}$');
  static final _locale = RegExp(r'^[a-z]{2}(-[A-Z]{2})?$');
  static final _email = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

  static String? e164(String? v) {
    final r = Validators.required(v, 'the phone number');
    if (r != null) return r;
    if (!_e164.hasMatch(v!.trim())) {
      return 'Use international format: + then the country code and number, e.g. +15551234567.';
    }
    return null;
  }

  static String? locale(String? v) {
    final r = Validators.required(v, 'a default locale');
    if (r != null) return r;
    if (!_locale.hasMatch(v!.trim())) return 'Use a language tag like en-US or es-DO.';
    return null;
  }

  static String? email(String? v, {bool optional = false}) {
    if (v == null || v.trim().isEmpty) return optional ? null : 'Enter a contact email.';
    if (!_email.hasMatch(v.trim())) return 'Enter an email address, e.g. ops@example.com.';
    return null;
  }
}

/// Provider-side lifecycle words → badge tone. Pending states read amber so
/// "waiting on someone else" never looks like "done".
YTone toneForStatus(String status) => switch (status) {
      'ACTIVE' || 'APPROVED' || 'VERIFIED' => YTone.success,
      'FAILED' || 'REJECTED' => YTone.danger,
      'SUBMITTING' || 'PENDING_REVIEW' || 'PENDING_VERIFICATION' || 'IN_PROGRESS' => YTone.warning,
      'SUSPENDED' || 'PAUSED' => YTone.info,
      _ => YTone.neutral,
    };

IconData iconForStatus(String status) => switch (status) {
      'ACTIVE' || 'APPROVED' || 'VERIFIED' => Icons.check_circle_outline,
      'FAILED' || 'REJECTED' => Icons.error_outline,
      'SUBMITTING' || 'PENDING_REVIEW' || 'PENDING_VERIFICATION' || 'IN_PROGRESS' => Icons.hourglass_top,
      'SUSPENDED' || 'PAUSED' => Icons.pause_circle_outline,
      'REVOKED' => Icons.block,
      _ => Icons.edit_note,
    };

/// Sentence-case label for an upper-snake wire value (`PENDING_REVIEW` → "Pending review").
String humanizeWire(String wire) {
  if (wire.isEmpty) return wire;
  final s = wire.replaceAll('_', ' ').toLowerCase();
  return s[0].toUpperCase() + s.substring(1);
}

/// A read-only label/value pair in a detail sheet.
class ChannelDetailRow extends StatelessWidget {
  const ChannelDetailRow({super.key, required this.label, required this.value, this.mono = false});
  final String label;
  final String value;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 168,
          child: Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: scheme.onSurfaceVariant)),
        ),
        Expanded(
          child: SelectableText(
            value,
            style: mono ? monoStyle(context, size: 13) : TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface),
          ),
        ),
      ]),
    );
  }
}

/// A heading inside a detail sheet.
class ChannelSheetHeading extends StatelessWidget {
  const ChannelSheetHeading(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: Gap.lg, bottom: Gap.sm),
      child: Row(children: [
        Expanded(
          child: Semantics(
            header: true,
            child: Text(text, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: scheme.onSurface)),
          ),
        ),
        ?trailing,
      ]),
    );
  }
}

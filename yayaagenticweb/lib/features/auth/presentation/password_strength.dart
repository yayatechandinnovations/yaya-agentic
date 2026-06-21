import 'package:flutter/material.dart';

/// Pure-client password-strength meter — matches the server's
/// `PasswordPolicy` (≥12 chars + ≥3 character classes). The server
/// is still authoritative; this only gates UI affordances.
class PasswordStrength {
  PasswordStrength({
    required this.length,
    required this.lower,
    required this.upper,
    required this.digit,
    required this.symbol,
  });

  final int length;
  final bool lower;
  final bool upper;
  final bool digit;
  final bool symbol;

  int get classCount =>
      (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);

  bool get meetsLength => length >= 12;
  bool get meetsClasses => classCount >= 3;
  bool get meetsPolicy => meetsLength && meetsClasses;

  /// 0..5 — drives the segmented meter.
  int get score {
    if (length == 0) return 0;
    int s = 0;
    if (length >= 8) s++;
    if (length >= 12) s++;
    if (length >= 16) s++;
    if (classCount >= 2) s++;
    if (classCount >= 3) s++;
    if (classCount >= 4) s++;
    return s.clamp(0, 6);
  }

  String get label {
    if (length == 0) return '';
    if (length < 12) return 'Too short';
    if (!meetsClasses) return 'Weak';
    if (score < 4) return 'OK';
    if (score < 6) return 'Strong';
    return 'Very strong';
  }

  static PasswordStrength of(String pw) {
    bool lower = false, upper = false, digit = false, symbol = false;
    for (final c in pw.codeUnits) {
      if (c >= 0x61 && c <= 0x7A) {
        lower = true;
      } else if (c >= 0x41 && c <= 0x5A) {
        upper = true;
      } else if (c >= 0x30 && c <= 0x39) {
        digit = true;
      } else {
        symbol = true;
      }
    }
    return PasswordStrength(
      length: pw.length,
      lower: lower,
      upper: upper,
      digit: digit,
      symbol: symbol,
    );
  }
}

/// Renders the 6-segment meter + caption + checklist for the signup and
/// reset-password screens.
class PasswordStrengthMeter extends StatelessWidget {
  const PasswordStrengthMeter({super.key, required this.password});
  final String password;

  @override
  Widget build(BuildContext context) {
    final s = PasswordStrength.of(password);
    final theme = Theme.of(context);
    final activeColor = switch (s.score) {
      <= 1 => theme.colorScheme.error,
      <= 3 => theme.colorScheme.tertiary,
      _ => theme.colorScheme.primary,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: List.generate(6, (i) {
            return Expanded(
              child: Container(
                height: 4,
                margin: EdgeInsets.only(right: i == 5 ? 0 : 3),
                decoration: BoxDecoration(
                  color: i < s.score
                      ? activeColor
                      : theme.colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            );
          }),
        ),
        const SizedBox(height: 6),
        Text(s.label,
            style:
                theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline)),
        const SizedBox(height: 4),
        Wrap(
          spacing: 12,
          runSpacing: 4,
          children: [
            _check(theme, '12+ chars', s.meetsLength),
            _check(theme, 'lowercase', s.lower),
            _check(theme, 'uppercase', s.upper),
            _check(theme, 'digit', s.digit),
            _check(theme, 'symbol', s.symbol),
          ],
        ),
      ],
    );
  }

  Widget _check(ThemeData theme, String label, bool met) {
    final color =
        met ? theme.colorScheme.primary : theme.colorScheme.outlineVariant;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(met ? Icons.check_circle : Icons.radio_button_unchecked,
            size: 14, color: color),
        const SizedBox(width: 4),
        Text(label, style: theme.textTheme.bodySmall?.copyWith(color: color)),
      ],
    );
  }
}

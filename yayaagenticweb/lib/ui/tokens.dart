import 'package:flutter/material.dart';

/// Status colours the Material scheme doesn't model (success, warning, info).
/// Every pair is tuned so the foreground reads at ≥4.5:1 on its container —
/// see test/ui/contrast_test.dart.
@immutable
class StatusColors extends ThemeExtension<StatusColors> {
  const StatusColors({
    required this.success,
    required this.successContainer,
    required this.onSuccessContainer,
    required this.warning,
    required this.warningContainer,
    required this.onWarningContainer,
    required this.info,
    required this.infoContainer,
    required this.onInfoContainer,
    required this.neutralContainer,
    required this.onNeutralContainer,
    required this.inputBorder,
    required this.highlight,
  });

  final Color success;
  final Color successContainer;
  final Color onSuccessContainer;
  final Color warning;
  final Color warningContainer;
  final Color onWarningContainer;
  final Color info;
  final Color infoContainer;
  final Color onInfoContainer;
  final Color neutralContainer;
  final Color onNeutralContainer;

  /// Enabled input outline: 3:1 against the surface (WCAG non-text contrast).
  final Color inputBorder;

  /// Background for a row that just changed (live updates).
  final Color highlight;

  static const light = StatusColors(
    success: Color(0xFF1E8A4C),
    successContainer: Color(0xFFE8F4EE),
    onSuccessContainer: Color(0xFF17603A),
    warning: Color(0xFFB86E00),
    warningContainer: Color(0xFFFDF3E1),
    onWarningContainer: Color(0xFF7A4500),
    info: Color(0xFF1F4FA8),
    infoContainer: Color(0xFFEAF1FD),
    onInfoContainer: Color(0xFF1F4FA8),
    neutralContainer: Color(0xFFEEF0F3),
    onNeutralContainer: Color(0xFF3D4350),
    inputBorder: Color(0xFF868D9A),
    highlight: Color(0xFFF2F4FF),
  );

  static const dark = StatusColors(
    success: Color(0xFF6FD49A),
    successContainer: Color(0xFF15301F),
    onSuccessContainer: Color(0xFF9BE3B8),
    warning: Color(0xFFF2B35B),
    warningContainer: Color(0xFF3A2A12),
    onWarningContainer: Color(0xFFF7CB8A),
    info: Color(0xFF9CBCFF),
    infoContainer: Color(0xFF1B2A47),
    onInfoContainer: Color(0xFFB9CFFF),
    neutralContainer: Color(0xFF2A2D33),
    onNeutralContainer: Color(0xFFD3D7DE),
    inputBorder: Color(0xFF7D8491),
    highlight: Color(0xFF1E2340),
  );

  static StatusColors of(BuildContext context) =>
      Theme.of(context).extension<StatusColors>() ?? light;

  @override
  StatusColors copyWith() => this;

  @override
  StatusColors lerp(StatusColors? other, double t) {
    if (other == null) return this;
    Color l(Color a, Color b) => Color.lerp(a, b, t)!;
    return StatusColors(
      success: l(success, other.success),
      successContainer: l(successContainer, other.successContainer),
      onSuccessContainer: l(onSuccessContainer, other.onSuccessContainer),
      warning: l(warning, other.warning),
      warningContainer: l(warningContainer, other.warningContainer),
      onWarningContainer: l(onWarningContainer, other.onWarningContainer),
      info: l(info, other.info),
      infoContainer: l(infoContainer, other.infoContainer),
      onInfoContainer: l(onInfoContainer, other.onInfoContainer),
      neutralContainer: l(neutralContainer, other.neutralContainer),
      onNeutralContainer: l(onNeutralContainer, other.onNeutralContainer),
      inputBorder: l(inputBorder, other.inputBorder),
      highlight: l(highlight, other.highlight),
    );
  }
}

/// Monospace for ids, versions, JSON and templates.
const monoFontFamily = 'monospace';

TextStyle monoStyle(BuildContext context, {double size = 13, FontWeight? weight, Color? color}) =>
    TextStyle(
      fontFamily: monoFontFamily,
      fontSize: size,
      fontWeight: weight,
      color: color ?? Theme.of(context).colorScheme.onSurface,
      height: 1.45,
    );

/// Layout constants shared by pages, sheets and forms.
abstract final class Gap {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 12.0;
  static const lg = 16.0;
  static const xl = 24.0;
  static const xxl = 32.0;
  static const pagePadding = 28.0;
  static const fieldGap = 18.0;
}

import 'package:flutter/material.dart';

/// Brand assets + palette for the Yaya Chat app. The logo is a neon lockup, so
/// the app runs a dark theme to let it (and the per-tile accents) glow.
class Brand {
  static const String logoAsset = 'assets/yaya-chat-logo.png';

  /// Neon endpoints of the logo gradient (magenta → cyan).
  static const Color magenta = Color(0xFFD000FF);
  static const Color cyan = Color(0xFF00C2FF);
  static const Color violet = Color(0xFF7A3CFF);
  static const Color surface = Color(0xFF0E0B1A);

  static const LinearGradient gradient = LinearGradient(
    colors: [magenta, violet, cyan],
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
  );

  /// Paint text (or any shader-able widget) with the brand gradient.
  static Widget gradientText(String text, {double fontSize = 22, FontWeight weight = FontWeight.w700}) {
    return ShaderMask(
      shaderCallback: (bounds) => gradient.createShader(bounds),
      child: Text(
        text,
        style: TextStyle(fontSize: fontSize, fontWeight: weight, color: Colors.white),
      ),
    );
  }
}

/// Parse an entry point's `#RRGGBB`/`#RRGGBBAA` accent into a [Color].
Color colorFromHex(String? hex, {Color fallback = Brand.violet}) {
  if (hex == null || hex.isEmpty) return fallback;
  var h = hex.replaceFirst('#', '').trim();
  if (h.length == 6) h = 'FF$h'; // assume opaque
  if (h.length != 8) return fallback;
  final value = int.tryParse(h, radix: 16);
  return value == null ? fallback : Color(value);
}

ThemeData buildBrandTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Brand.violet,
    brightness: Brightness.dark,
  ).copyWith(surface: Brand.surface);
  return ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: Brand.surface,
    useMaterial3: true,
    appBarTheme: const AppBarTheme(
      backgroundColor: Brand.surface,
      elevation: 0,
      centerTitle: true,
    ),
  );
}

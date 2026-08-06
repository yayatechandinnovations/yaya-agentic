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

/// Light theme (chat-experience-design.md §9): receipts and forums are daytime
/// surfaces, so the app follows the system. The neon wordmark + per-tile
/// accents keep the brand identity in both modes.
ThemeData buildBrandTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Brand.violet,
    brightness: Brightness.light,
  );
  return _base(scheme).copyWith(
    scaffoldBackgroundColor: scheme.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      elevation: 0,
      centerTitle: true,
    ),
  );
}

ThemeData buildBrandThemeDark() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Brand.violet,
    brightness: Brightness.dark,
  ).copyWith(surface: Brand.surface);
  return _base(scheme).copyWith(
    scaffoldBackgroundColor: Brand.surface,
    appBarTheme: const AppBarTheme(
      backgroundColor: Brand.surface,
      elevation: 0,
      centerTitle: true,
    ),
  );
}

ThemeData _base(ColorScheme scheme) {
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    navigationBarTheme: NavigationBarThemeData(
      height: 64,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      indicatorColor: scheme.primaryContainer,
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}

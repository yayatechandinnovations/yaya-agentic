import 'package:flutter/material.dart';

/// Brand assets + palette for the Yaya Chat app. The logo is a blue circuit-Y
/// lockup on white; it's rendered on a white "logo tile" so it reads on both the
/// dark brand surface and the light theme.
class Brand {
  /// Full horizontal lockup (mark + "Yaya Agentic"). Hero/brand moments.
  static const String logoAsset = 'assets/yaya-chat-logo.png';

  /// Square mark only (the circuit-Y speech bubble). Compact spots + the app
  /// icon. Also on white, so it's shown via [mark] on a tile.
  static const String markAsset = 'assets/yayachat-logo-1.png';

  /// Neon endpoints of the accent gradient (magenta → cyan).
  static const Color magenta = Color(0xFFD000FF);
  static const Color cyan = Color(0xFF00C2FF);
  static const Color violet = Color(0xFF7A3CFF);
  static const Color surface = Color(0xFF0E0B1A);

  static const LinearGradient gradient = LinearGradient(
    colors: [magenta, violet, cyan],
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
  );

  /// The full wordmark on a white tile — for hero moments (onboarding, empty
  /// states). [height] is the logo height; the tile adds padding around it.
  static Widget wordmark({double height = 160}) =>
      _logoTile(logoAsset, height: height, padding: height * 0.07, radius: 24);

  /// The compact square mark on a white tile — for small brand spots (splash,
  /// lock, PIN) where the wide wordmark would be too small to read.
  static Widget mark({double size = 96}) => _logoTile(markAsset,
      height: size, width: size, padding: size * 0.05, radius: size * 0.24);

  static Widget _logoTile(String asset,
      {double? height, double? width, double padding = 12, double radius = 20}) {
    return Container(
      padding: EdgeInsets.all(padding),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(radius),
        boxShadow: const [
          BoxShadow(color: Color(0x22000000), blurRadius: 16, offset: Offset(0, 6)),
        ],
      ),
      child: Image.asset(asset, height: height, width: width, fit: BoxFit.contain),
    );
  }

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

import 'package:flutter/material.dart';

/// Brand constants lifted from assets/yaya-identity-logo.png: a magenta→cyan
/// gradient mark on deep navy. The console keeps surfaces minimal and light and
/// spends the gradient only on identity moments (logo, sign-in, accents).
class AppBrand {
  static const navy = Color(0xFF0A0E27);
  static const navyLight = Color(0xFF161C42);
  static const magenta = Color(0xFFC026D3);
  static const violet = Color(0xFF7C3AED);
  static const cyan = Color(0xFF06B6D4);

  static const gradient = LinearGradient(
    colors: [magenta, violet, cyan],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  static const logoAsset = 'assets/yaya-identity-logo.png';
}

class AppTheme {
  static ThemeData get light {
    final scheme = ColorScheme.fromSeed(seedColor: AppBrand.violet);
    return ThemeData(
      colorScheme: scheme,
      useMaterial3: true,
      visualDensity: VisualDensity.comfortable,
      scaffoldBackgroundColor: const Color(0xFFF8F8FC),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xFFF8F8FC),
        foregroundColor: AppBrand.navy,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleTextStyle: TextStyle(
          color: AppBrand.navy,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: Colors.black.withValues(alpha: 0.07)),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFFF3F4FA),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: AppBrand.violet, width: 1.6),
        ),
      ),
      chipTheme: const ChipThemeData(
        side: BorderSide(color: Color(0xFFE3E4F0)),
        backgroundColor: Color(0xFFF3F4FA),
        labelStyle: TextStyle(fontSize: 12),
      ),
      navigationRailTheme: NavigationRailThemeData(
        backgroundColor: AppBrand.navy,
        indicatorColor: AppBrand.violet.withValues(alpha: 0.45),
        selectedIconTheme: const IconThemeData(color: Colors.white),
        unselectedIconTheme: IconThemeData(color: Colors.white.withValues(alpha: 0.55)),
        selectedLabelTextStyle:
            const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600),
        unselectedLabelTextStyle:
            TextStyle(color: Colors.white.withValues(alpha: 0.55), fontSize: 12),
      ),
      dividerTheme: DividerThemeData(color: Colors.black.withValues(alpha: 0.07)),
    );
  }
}

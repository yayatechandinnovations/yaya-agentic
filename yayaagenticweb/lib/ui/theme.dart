import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'tokens.dart';

const _brand = Color(0xFF3D4ED8);

/// Light scheme. `outline` is deliberately dark enough (≥4.5:1 on every
/// surface container) to be used as secondary text, because 60+ call sites
/// already do; borders get their own lighter token.
ColorScheme lightScheme() => ColorScheme.fromSeed(seedColor: _brand).copyWith(
      primary: _brand,
      onPrimary: Colors.white,
      primaryContainer: const Color(0xFFE6E9F8),
      onPrimaryContainer: const Color(0xFF2A37A8),
      secondaryContainer: const Color(0xFFE6E9F8),
      onSecondaryContainer: const Color(0xFF2A37A8),
      surface: Colors.white,
      onSurface: const Color(0xFF14161B),
      onSurfaceVariant: const Color(0xFF4A5160),
      surfaceContainerLowest: Colors.white,
      surfaceContainerLow: const Color(0xFFFBFBFC),
      surfaceContainer: const Color(0xFFF4F5F7),
      surfaceContainerHigh: const Color(0xFFEEF0F3),
      surfaceContainerHighest: const Color(0xFFE9EBEF),
      outline: const Color(0xFF5F6673),
      outlineVariant: const Color(0xFFDADDE3),
      error: const Color(0xFFB3261E),
      onError: Colors.white,
      errorContainer: const Color(0xFFFCEBEA),
      onErrorContainer: const Color(0xFF8C1D18),
    );

ColorScheme darkScheme() =>
    ColorScheme.fromSeed(seedColor: _brand, brightness: Brightness.dark).copyWith(
      primary: const Color(0xFFAEB8FF),
      onPrimary: const Color(0xFF14196B),
      primaryContainer: const Color(0xFF2B3590),
      onPrimaryContainer: const Color(0xFFDDE1FF),
      secondaryContainer: const Color(0xFF2B3590),
      onSecondaryContainer: const Color(0xFFDDE1FF),
      surface: const Color(0xFF111318),
      onSurface: const Color(0xFFE6E8EC),
      onSurfaceVariant: const Color(0xFFB4BAC5),
      surfaceContainerLowest: const Color(0xFF0C0E12),
      surfaceContainerLow: const Color(0xFF16181E),
      surfaceContainer: const Color(0xFF1B1E24),
      surfaceContainerHigh: const Color(0xFF25282F),
      surfaceContainerHighest: const Color(0xFF2E3138),
      outline: const Color(0xFF9AA1AD),
      outlineVariant: const Color(0xFF3A3E46),
      error: const Color(0xFFFFB4AB),
      onError: const Color(0xFF690005),
      errorContainer: const Color(0xFF5C1712),
      onErrorContainer: const Color(0xFFFFDAD6),
    );

ThemeData buildTheme(Brightness brightness) {
  final scheme = brightness == Brightness.light ? lightScheme() : darkScheme();
  final status = brightness == Brightness.light ? StatusColors.light : StatusColors.dark;
  final base = ThemeData(colorScheme: scheme, useMaterial3: true);
  final text = base.textTheme.copyWith(
    // M3 defaults put helpers/captions at 11–12px; 13 is the floor for
    // anything an operator has to read.
    bodySmall: base.textTheme.bodySmall?.copyWith(fontSize: 13, height: 1.45),
    labelSmall: base.textTheme.labelSmall?.copyWith(fontSize: 12),
    labelMedium: base.textTheme.labelMedium?.copyWith(fontSize: 13),
    headlineSmall: base.textTheme.headlineSmall
        ?.copyWith(fontSize: 26, fontWeight: FontWeight.w600, letterSpacing: -0.2),
    titleLarge: base.textTheme.titleLarge?.copyWith(fontSize: 20, fontWeight: FontWeight.w600),
    titleMedium: base.textTheme.titleMedium?.copyWith(fontSize: 16, fontWeight: FontWeight.w600),
  ).apply(bodyColor: scheme.onSurface, displayColor: scheme.onSurface);

  OutlineInputBorder border(Color c, [double w = 1]) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: BorderSide(color: c, width: w),
      );

  return base.copyWith(
    textTheme: text,
    scaffoldBackgroundColor: scheme.surfaceContainer,
    extensions: [status],
    dividerTheme: DividerThemeData(color: scheme.outlineVariant, space: 1, thickness: 1),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surface,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      border: border(status.inputBorder),
      enabledBorder: border(status.inputBorder),
      focusedBorder: border(scheme.primary, 2),
      errorBorder: border(scheme.error, 2),
      focusedErrorBorder: border(scheme.error, 2),
      disabledBorder: border(scheme.outlineVariant),
      hintStyle: TextStyle(color: scheme.outline),
      helperStyle: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13, height: 1.45),
      errorStyle: TextStyle(color: scheme.error, fontSize: 13, height: 1.45, fontWeight: FontWeight.w500),
      // Unset, helper and error text are cut to one line with an ellipsis.
      helperMaxLines: 4,
      errorMaxLines: 4,
    ),
    cardTheme: CardThemeData(
      color: scheme.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, 40),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(64, 40),
        foregroundColor: scheme.onSurface,
        side: BorderSide(color: status.inputBorder),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      width: 520,
      backgroundColor: scheme.inverseSurface,
      contentTextStyle: TextStyle(color: scheme.onInverseSurface, fontSize: 14),
      actionTextColor: scheme.inversePrimary,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ),
    tooltipTheme: const TooltipThemeData(waitDuration: Duration(milliseconds: 400)),
    chipTheme: base.chipTheme.copyWith(
      labelStyle: TextStyle(color: scheme.onSurface, fontSize: 13),
      side: BorderSide(color: status.inputBorder),
    ),
  );
}

/// Operator's theme choice, persisted per browser. System is the default.
class ThemeModeController extends Notifier<ThemeMode> {
  static const _key = 'yaya.themeMode';

  @override
  ThemeMode build() {
    SharedPreferences.getInstance().then((p) {
      final stored = p.getString(_key);
      final mode = ThemeMode.values.where((m) => m.name == stored).firstOrNull;
      if (mode != null && mode != state) state = mode;
    });
    return ThemeMode.system;
  }

  Future<void> set(ThemeMode mode) async {
    state = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, mode.name);
  }
}

final themeModeProvider = NotifierProvider<ThemeModeController, ThemeMode>(ThemeModeController.new);

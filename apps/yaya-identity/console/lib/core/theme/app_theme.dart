import 'package:flutter/material.dart';

class AppTheme {
  static ThemeData get light => ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3A5BDC)),
        useMaterial3: true,
        visualDensity: VisualDensity.comfortable,
      );
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/ui/theme.dart';
import 'package:yayaagenticweb/ui/tokens.dart';

double _contrast(Color a, Color b) {
  final la = a.computeLuminance(), lb = b.computeLuminance();
  final hi = la > lb ? la : lb, lo = la > lb ? lb : la;
  return (hi + 0.05) / (lo + 0.05);
}

void main() {
  for (final brightness in Brightness.values) {
    group('${brightness.name} theme', () {
      final theme = buildTheme(brightness);
      final s = theme.colorScheme;
      final st = theme.extension<StatusColors>()!;
      final surfaces = {
        'surface': s.surface,
        'surfaceContainerLowest': s.surfaceContainerLowest,
        'surfaceContainerLow': s.surfaceContainerLow,
        'surfaceContainer': s.surfaceContainer,
        'surfaceContainerHigh': s.surfaceContainerHigh,
        'surfaceContainerHighest': s.surfaceContainerHighest,
      };

      // outline is in the list on purpose: dozens of screens use it as
      // secondary text, so it has to read as text everywhere.
      for (final (name, fg) in [('onSurface', s.onSurface), ('onSurfaceVariant', s.onSurfaceVariant), ('outline', s.outline), ('primary', s.primary), ('error', s.error)]) {
        for (final e in surfaces.entries) {
          test('$name on ${e.key} is at least 4.5:1', () {
            expect(_contrast(fg, e.value), greaterThanOrEqualTo(4.5));
          });
        }
      }

      test('input borders are at least 3:1 against the surface', () {
        expect(_contrast(st.inputBorder, s.surface), greaterThanOrEqualTo(3.0));
      });

      for (final (name, fg, bg) in [
        ('success', st.onSuccessContainer, st.successContainer),
        ('warning', st.onWarningContainer, st.warningContainer),
        ('info', st.onInfoContainer, st.infoContainer),
        ('neutral', st.onNeutralContainer, st.neutralContainer),
        ('error', s.onErrorContainer, s.errorContainer),
        ('primary container', s.onPrimaryContainer, s.primaryContainer),
        ('toast', s.onInverseSurface, s.inverseSurface),
      ]) {
        test('$name text reads at least 4.5:1 on its container', () {
          expect(_contrast(fg, bg), greaterThanOrEqualTo(4.5));
        });
      }
    });
  }
}

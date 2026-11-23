import 'package:flutter/material.dart';

import '../tokens.dart';

enum YTone { neutral, info, success, warning, danger, brand }

/// Status/category chip: always a word (and optionally an icon), never colour alone.
class YBadge extends StatelessWidget {
  const YBadge(this.label, {super.key, this.tone = YTone.neutral, this.icon, this.mono = false});
  final String label;
  final YTone tone;
  final IconData? icon;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = toneColors(context, tone);
    return Container(
      height: 24,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: 14, color: fg), const SizedBox(width: 4)],
        Flexible(
          child: Text(label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: mono
                ? monoStyle(context, size: 12, weight: FontWeight.w500, color: fg)
                : TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: fg)),
        ),
      ]),
    );
  }
}

(Color, Color) toneColors(BuildContext context, YTone tone) {
  final s = StatusColors.of(context);
  final scheme = Theme.of(context).colorScheme;
  return switch (tone) {
    YTone.neutral => (s.neutralContainer, s.onNeutralContainer),
    YTone.info => (s.infoContainer, s.onInfoContainer),
    YTone.success => (s.successContainer, s.onSuccessContainer),
    YTone.warning => (s.warningContainer, s.onWarningContainer),
    YTone.danger => (scheme.errorContainer, scheme.onErrorContainer),
    YTone.brand => (scheme.primaryContainer, scheme.onPrimaryContainer),
  };
}

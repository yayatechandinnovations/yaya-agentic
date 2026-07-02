import 'package:flutter/material.dart';

/// The `LOGO` half of the `LOGO | NAME` brand mark (chat-channel-design.md §3).
/// A network logo over an accent circle, falling back to the name's initial, so
/// the directory tiles, discover results, and the chat header all render the
/// same branding.
class BrandLogo extends StatelessWidget {
  const BrandLogo({
    super.key,
    required this.url,
    required this.name,
    required this.accent,
    this.radius = 20,
  });

  final String url;
  final String name;
  final Color accent;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final initials = name.isEmpty ? '?' : name.trim()[0].toUpperCase();
    final diameter = radius * 2;
    return CircleAvatar(
      radius: radius,
      backgroundColor: accent,
      foregroundColor: Colors.white,
      child: ClipOval(
        child: Image.network(
          url,
          width: diameter,
          height: diameter,
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => Text(initials),
        ),
      ),
    );
  }
}

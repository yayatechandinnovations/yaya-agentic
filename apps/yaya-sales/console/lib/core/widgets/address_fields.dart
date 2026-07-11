import 'package:flutter/material.dart';

/// Controller bundle for the structured address form (mirrors the platform
/// Address record on the wire).
class AddressDraft {
  AddressDraft({Map<String, dynamic>? from})
      : line1 = TextEditingController(text: _s(from, 'line1')),
        line2 = TextEditingController(text: _s(from, 'line2')),
        city = TextEditingController(text: _s(from, 'city')),
        region = TextEditingController(text: _s(from, 'region')),
        postalCode = TextEditingController(text: _s(from, 'postalCode')),
        country = TextEditingController(text: _s(from, 'country'));

  final TextEditingController line1;
  final TextEditingController line2;
  final TextEditingController city;
  final TextEditingController region;
  final TextEditingController postalCode;
  final TextEditingController country;

  bool get isEmpty => [line1, line2, city, region, postalCode, country]
      .every((c) => c.text.trim().isEmpty);

  Map<String, dynamic> toJson() => {
        'line1': _v(line1),
        'line2': _v(line2),
        'city': _v(city),
        'region': _v(region),
        'postalCode': _v(postalCode),
        'country': _v(country),
      };

  void dispose() {
    for (final c in [line1, line2, city, region, postalCode, country]) {
      c.dispose();
    }
  }

  static String _s(Map<String, dynamic>? from, String key) =>
      from == null || from[key] == null ? '' : '${from[key]}';

  static String? _v(TextEditingController c) =>
      c.text.trim().isEmpty ? null : c.text.trim();
}

/// The structured address form — one look everywhere an address is captured.
class AddressFields extends StatelessWidget {
  const AddressFields({super.key, required this.draft});

  final AddressDraft draft;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          controller: draft.line1,
          decoration: const InputDecoration(labelText: 'Address line 1'),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: draft.line2,
          decoration: const InputDecoration(labelText: 'Address line 2 (optional)'),
        ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(
            child: TextField(
              controller: draft.city,
              decoration: const InputDecoration(labelText: 'City'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: draft.region,
              decoration: const InputDecoration(labelText: 'State / province'),
            ),
          ),
        ]),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(
            child: TextField(
              controller: draft.postalCode,
              decoration: const InputDecoration(labelText: 'Postal code'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: draft.country,
              decoration: const InputDecoration(
                  labelText: 'Country', helperText: 'ISO code, e.g. DO'),
            ),
          ),
        ]),
      ],
    );
  }
}

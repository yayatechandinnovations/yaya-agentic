import 'package:equatable/equatable.dart';

class CustomerAddress extends Equatable {
  const CustomerAddress({
    required this.id,
    required this.kind,
    this.line1,
    this.line2,
    this.city,
    this.region,
    this.postalCode,
    this.country,
  });

  final String id;
  final String kind; // SHIPPING | BILLING | OTHER
  final String? line1;
  final String? line2;
  final String? city;
  final String? region;
  final String? postalCode;
  final String? country;

  String get oneLine => [line1, line2, city, region, postalCode, country]
      .where((p) => p != null && p.isNotEmpty)
      .join(', ');

  @override
  List<Object?> get props => [id, kind, line1, line2, city, region, postalCode, country];
}

class Customer extends Equatable {
  const Customer({
    required this.id,
    required this.displayName,
    this.customerRef,
    this.email,
    this.phone,
    this.notes,
    this.addresses = const [],
    this.createdAt,
  });

  final String id;
  final String displayName;
  final String? customerRef;
  final String? email;
  final String? phone;
  final String? notes;
  final List<CustomerAddress> addresses;
  final DateTime? createdAt;

  String get initials {
    final parts = displayName.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) return '?';
    return parts.take(2).map((p) => p[0].toUpperCase()).join();
  }

  @override
  List<Object?> get props =>
      [id, displayName, customerRef, email, phone, notes, addresses, createdAt];
}

/// Create/update input (mirrors the admin Draft on the wire).
class CustomerDraft extends Equatable {
  const CustomerDraft({
    required this.displayName,
    this.customerRef,
    this.email,
    this.phone,
    this.notes,
  });

  final String displayName;
  final String? customerRef;
  final String? email;
  final String? phone;
  final String? notes;

  @override
  List<Object?> get props => [displayName, customerRef, email, phone, notes];
}

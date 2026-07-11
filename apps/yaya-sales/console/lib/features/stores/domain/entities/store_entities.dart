import 'package:equatable/equatable.dart';

/// Store + selling-context read models mirroring the yaya-sales admin wire
/// shapes (StoreView, StorePolicies, LocationView, ChannelView, RegionView).

class Store extends Equatable {
  const Store({
    required this.id,
    required this.tenantId,
    required this.displayName,
    required this.defaultCurrency,
    required this.policies,
  });

  final String id;
  final String tenantId;
  final String displayName;
  final String defaultCurrency;
  final StorePolicies policies;

  @override
  List<Object?> get props => [id, tenantId, displayName, defaultCurrency, policies];
}

class StorePolicies extends Equatable {
  const StorePolicies({
    required this.deductionPoint,
    required this.backorder,
    required this.softHoldTtlSeconds,
    required this.checkoutHoldTtlSeconds,
  });

  final String deductionPoint; // ON_PAYMENT | ON_FULFILLMENT
  final String backorder; // DENY | ALLOW | PREORDER
  final int softHoldTtlSeconds;
  final int checkoutHoldTtlSeconds;

  static const deductionPoints = ['ON_PAYMENT', 'ON_FULFILLMENT'];
  static const backorderPolicies = ['DENY', 'ALLOW', 'PREORDER'];

  @override
  List<Object?> get props =>
      [deductionPoint, backorder, softHoldTtlSeconds, checkoutHoldTtlSeconds];
}

class AddressInfo extends Equatable {
  const AddressInfo({
    this.line1,
    this.line2,
    this.city,
    this.region,
    this.postalCode,
    this.country,
  });

  final String? line1;
  final String? line2;
  final String? city;
  final String? region;
  final String? postalCode;
  final String? country;

  String get oneLine => [line1, line2, city, region, postalCode, country]
      .where((p) => p != null && p.isNotEmpty)
      .join(', ');

  Map<String, dynamic> toJson() => {
        'line1': line1,
        'line2': line2,
        'city': city,
        'region': region,
        'postalCode': postalCode,
        'country': country,
      };

  @override
  List<Object?> get props => [line1, line2, city, region, postalCode, country];
}

class StoreLocation extends Equatable {
  const StoreLocation({
    required this.id,
    required this.label,
    required this.kind,
    required this.sellable,
    this.address,
  });

  final String id;
  final String label;
  final String kind; // WAREHOUSE | SHOP | VIRTUAL
  final bool sellable;
  final AddressInfo? address;

  static const kinds = ['WAREHOUSE', 'SHOP', 'VIRTUAL'];

  @override
  List<Object?> get props => [id, label, kind, sellable, address];
}

class Channel extends Equatable {
  const Channel({
    required this.id,
    required this.kind,
    required this.label,
    required this.active,
  });

  final String id;
  final String kind; // WEB_CHAT | WHATSAPP | SMS | POS
  final String label;
  final bool active;

  static const kinds = ['WEB_CHAT', 'WHATSAPP', 'SMS', 'POS'];

  @override
  List<Object?> get props => [id, kind, label, active];
}

class TaxPolicy extends Equatable {
  const TaxPolicy({required this.inclusive, required this.rateBps, required this.label});

  final bool inclusive;
  final int rateBps;
  final String label;

  @override
  List<Object?> get props => [inclusive, rateBps, label];
}

class Region extends Equatable {
  const Region({
    required this.id,
    required this.name,
    required this.currency,
    required this.tax,
    required this.tenders,
  });

  final String id;
  final String name;
  final String currency;
  final TaxPolicy tax;
  final List<String> tenders; // PSP | COD | MANUAL | STORE_CREDIT

  static const tenderKinds = ['PSP', 'COD', 'MANUAL', 'STORE_CREDIT'];

  @override
  List<Object?> get props => [id, name, currency, tax, tenders];
}

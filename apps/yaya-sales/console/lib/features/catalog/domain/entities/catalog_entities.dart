import 'package:equatable/equatable.dart';

import '../../../../core/domain/money.dart';

class Catalog extends Equatable {
  const Catalog({
    required this.id,
    required this.version,
    required this.displayName,
    required this.status,
    this.coverUrl,
  });

  final String id;
  final int version;
  final String displayName;
  final String status; // DRAFT | PUBLISHED | DEPRECATED
  final String? coverUrl;

  @override
  List<Object?> get props => [id, version, displayName, status, coverUrl];
}

class Variant extends Equatable {
  const Variant({
    required this.sku,
    required this.label,
    this.price,
    this.options = const {},
    this.weightGrams,
    this.barcode,
  });

  final String sku;
  final String label;
  final Money? price;
  final Map<String, String> options;
  final int? weightGrams;
  final String? barcode;

  @override
  List<Object?> get props => [sku, label, price, options, weightGrams, barcode];
}

class Product extends Equatable {
  const Product({
    required this.id,
    required this.title,
    required this.description,
    required this.category,
    required this.status,
    required this.sortOrder,
    this.attributes = const {},
    this.media = const [],
    this.metadata = const {},
    this.variants = const [],
  });

  final String id;
  final String title;
  final String description;
  final String category;
  final String status; // ACTIVE | ARCHIVED | …
  final int sortOrder;
  final Map<String, String> attributes;
  final List<String> media;
  final Map<String, dynamic> metadata;
  final List<Variant> variants;

  @override
  List<Object?> get props =>
      [id, title, description, category, status, sortOrder, attributes, media, metadata, variants];
}

/// Authoring input for create/update (mirrors ProductDraft on the wire).
class ProductDraft extends Equatable {
  const ProductDraft({
    required this.title,
    required this.description,
    required this.category,
    required this.status,
    required this.sortOrder,
    this.attributes = const {},
    this.media = const [],
    this.metadata = const {},
    this.variants = const [],
  });

  final String title;
  final String description;
  final String category;
  final String? status;
  final int sortOrder;
  final Map<String, String> attributes;
  final List<String> media;
  final Map<String, dynamic> metadata;
  final List<Variant> variants;

  @override
  List<Object?> get props =>
      [title, description, category, status, sortOrder, attributes, media, metadata, variants];
}

class ImportRowError extends Equatable {
  const ImportRowError({required this.row, required this.message});

  final int row;
  final String message;

  @override
  List<Object?> get props => [row, message];
}

class ImportResult extends Equatable {
  const ImportResult({required this.created, required this.updated, required this.errors});

  final int created;
  final int updated;
  final List<ImportRowError> errors;

  @override
  List<Object?> get props => [created, updated, errors];
}

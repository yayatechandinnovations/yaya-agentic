import 'package:equatable/equatable.dart';

class Organization extends Equatable {
  const Organization({
    required this.id,
    required this.name,
    required this.slug,
    this.status = 'ACTIVE',
    this.mfaRequired = false,
    this.description,
    this.contactEmail,
    this.contactPhone,
    this.website,
    this.country,
    this.createdAt,
    this.memberCount = 0,
  });

  final String id;
  final String name;
  final String slug;
  final String status;
  final bool mfaRequired;
  final String? description;
  final String? contactEmail;
  final String? contactPhone;
  final String? website;
  final String? country;
  final String? createdAt;
  final int memberCount;

  @override
  List<Object?> get props => [
        id, name, slug, status, mfaRequired, description,
        contactEmail, contactPhone, website, country, createdAt, memberCount,
      ];
}

/// What an operator types when creating or editing an org — everything but the
/// server-owned id/slug/status.
class OrgDraft extends Equatable {
  const OrgDraft({
    required this.name,
    this.description,
    this.contactEmail,
    this.contactPhone,
    this.website,
    this.country,
  });

  final String name;
  final String? description;
  final String? contactEmail;
  final String? contactPhone;
  final String? website;
  final String? country;

  @override
  List<Object?> get props =>
      [name, description, contactEmail, contactPhone, website, country];
}

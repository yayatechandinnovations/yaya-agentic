import 'package:equatable/equatable.dart';

class Organization extends Equatable {
  const Organization({
    required this.id,
    required this.name,
    required this.slug,
    this.mfaRequired = false,
  });

  final String id;
  final String name;
  final String slug;
  final bool mfaRequired;

  @override
  List<Object?> get props => [id, name, slug, mfaRequired];
}

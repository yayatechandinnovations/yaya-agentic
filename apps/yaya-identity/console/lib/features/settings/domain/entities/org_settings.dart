import 'package:equatable/equatable.dart';

class OrgSettings extends Equatable {
  const OrgSettings({required this.org, required this.name, required this.mfaRequired});

  final String org;
  final String name;
  final bool mfaRequired;

  @override
  List<Object?> get props => [org, name, mfaRequired];
}

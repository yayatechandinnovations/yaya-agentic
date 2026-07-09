import 'package:equatable/equatable.dart';

/// A hypothetical decision to dry-run through the PDP simulator.
class DecisionQuery extends Equatable {
  const DecisionQuery({
    required this.org,
    required this.subject,
    required this.action,
    this.resourceType,
    this.resourceId,
  });

  final String org;
  final String subject;
  final String action;
  final String? resourceType;
  final String? resourceId;

  @override
  List<Object?> get props => [org, subject, action, resourceType, resourceId];
}

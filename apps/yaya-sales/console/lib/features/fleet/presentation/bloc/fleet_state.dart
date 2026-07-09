part of 'fleet_bloc.dart';

enum FleetStatus { initial, loading, loaded, failure }

class FleetState extends Equatable {
  const FleetState({
    this.status = FleetStatus.initial,
    this.agents = const [],
    this.statusFilter,
    this.selected,
    this.mintedInvite,
    this.mintedCredential,
    this.documentId,
    this.documentContent,
    this.error,
  });

  final FleetStatus status;
  final List<DeliveryAgent> agents;
  final String? statusFilter;
  final DeliveryAgent? selected;

  /// One-time values — set by exactly one emission, cleared on the next.
  final FleetInvite? mintedInvite;
  final CourierCredential? mintedCredential;

  final String? documentId;
  final String? documentContent;
  final String? error;

  FleetState copyWith({
    FleetStatus? status,
    List<DeliveryAgent>? agents,
    String? statusFilter,
    DeliveryAgent? selected,
    FleetInvite? mintedInvite,
    CourierCredential? mintedCredential,
    String? documentId,
    String? documentContent,
    String? error,
  }) =>
      FleetState(
        status: status ?? this.status,
        agents: agents ?? this.agents,
        statusFilter: statusFilter ?? this.statusFilter,
        selected: selected ?? this.selected,
        mintedInvite: mintedInvite,
        mintedCredential: mintedCredential,
        documentId: documentId ?? this.documentId,
        documentContent: documentContent ?? this.documentContent,
        error: error,
      );

  @override
  List<Object?> get props => [
        status,
        agents,
        statusFilter,
        selected,
        mintedInvite,
        mintedCredential,
        documentId,
        documentContent,
        error,
      ];
}

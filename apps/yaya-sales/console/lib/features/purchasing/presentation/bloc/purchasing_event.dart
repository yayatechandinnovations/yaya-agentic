part of 'purchasing_bloc.dart';

sealed class PurchasingEvent extends Equatable {
  const PurchasingEvent();

  @override
  List<Object?> get props => [];
}

class PurchasingRequested extends PurchasingEvent {
  const PurchasingRequested({this.statusFilter});

  final String? statusFilter;

  @override
  List<Object?> get props => [statusFilter];
}

class SupplierSaveRequested extends PurchasingEvent {
  const SupplierSaveRequested({this.id, required this.name, this.contact = const {}});

  final String? id; // null = create
  final String name;
  final Map<String, dynamic> contact;

  @override
  List<Object?> get props => [id, name, contact];
}

class SupplierDeleteRequested extends PurchasingEvent {
  const SupplierDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

class PoCreateRequested extends PurchasingEvent {
  const PoCreateRequested({
    required this.supplierId,
    required this.locationId,
    required this.lines,
    this.expectedAt,
  });

  final String supplierId;
  final String locationId;
  final List<PoLine> lines;
  final DateTime? expectedAt;

  @override
  List<Object?> get props => [supplierId, locationId, lines, expectedAt];
}

class PoPlaceRequested extends PurchasingEvent {
  const PoPlaceRequested(this.poId);

  final String poId;

  @override
  List<Object?> get props => [poId];
}

class PoCancelRequested extends PurchasingEvent {
  const PoCancelRequested(this.poId);

  final String poId;

  @override
  List<Object?> get props => [poId];
}

class PoReceiveRequested extends PurchasingEvent {
  const PoReceiveRequested(this.poId, this.lines);

  final String poId;
  final List<PoLine> lines;

  @override
  List<Object?> get props => [poId, lines];
}

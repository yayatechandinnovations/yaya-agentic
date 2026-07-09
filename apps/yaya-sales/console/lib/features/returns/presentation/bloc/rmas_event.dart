part of 'rmas_bloc.dart';

sealed class RmasEvent extends Equatable {
  const RmasEvent();

  @override
  List<Object?> get props => [];
}

class RmasRequested extends RmasEvent {
  const RmasRequested({this.statusFilter});

  final String? statusFilter;

  @override
  List<Object?> get props => [statusFilter];
}

class RmaDetailRequested extends RmasEvent {
  const RmaDetailRequested(this.rmaId);

  final String rmaId;

  @override
  List<Object?> get props => [rmaId];
}

/// Load the order's lines so the create dialog can offer a qty picker.
class RmaOrderLookupRequested extends RmasEvent {
  const RmaOrderLookupRequested(this.orderId);

  final String orderId;

  @override
  List<Object?> get props => [orderId];
}

class RmaCreateRequested extends RmasEvent {
  const RmaCreateRequested(this.orderId, this.lines, this.reason);

  final String orderId;
  final List<RmaLine> lines;
  final String? reason;

  @override
  List<Object?> get props => [orderId, lines, reason];
}

class RmaAuthorizeRequested extends RmasEvent {
  const RmaAuthorizeRequested(this.rmaId);

  final String rmaId;

  @override
  List<Object?> get props => [rmaId];
}

class RmaRejectRequested extends RmasEvent {
  const RmaRejectRequested(this.rmaId, this.reason);

  final String rmaId;
  final String? reason;

  @override
  List<Object?> get props => [rmaId, reason];
}

class RmaCancelRequested extends RmasEvent {
  const RmaCancelRequested(this.rmaId);

  final String rmaId;

  @override
  List<Object?> get props => [rmaId];
}

class RmaReturnShipmentSaved extends RmasEvent {
  const RmaReturnShipmentSaved(this.rmaId, this.info);

  final String rmaId;
  final ReturnShipmentInfo info;

  @override
  List<Object?> get props => [rmaId, info];
}

class RmaReceiveRequested extends RmasEvent {
  const RmaReceiveRequested(this.rmaId, this.dispositions, this.refundTo, this.amountMinor);

  final String rmaId;
  final List<RmaDisposition> dispositions;
  final String refundTo; // store_credit | original_tender
  final int? amountMinor;

  @override
  List<Object?> get props => [rmaId, dispositions, refundTo, amountMinor];
}

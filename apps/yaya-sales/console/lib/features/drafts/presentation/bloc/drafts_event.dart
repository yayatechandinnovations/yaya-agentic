part of 'drafts_bloc.dart';

sealed class DraftsEvent extends Equatable {
  const DraftsEvent();

  @override
  List<Object?> get props => [];
}

class DraftsRequested extends DraftsEvent {
  const DraftsRequested();
}

class DraftSkusRequested extends DraftsEvent {
  const DraftSkusRequested();
}

class DraftCreateRequested extends DraftsEvent {
  const DraftCreateRequested({
    this.channel,
    this.regionId,
    this.customerRef,
    this.note,
    required this.lines,
  });

  final String? channel;
  final String? regionId;
  final String? customerRef;
  final String? note;
  final List<DraftLine> lines;

  @override
  List<Object?> get props => [channel, regionId, customerRef, note, lines];
}

class DraftUpdateRequested extends DraftsEvent {
  const DraftUpdateRequested(this.orderId, this.lines);

  final String orderId;
  final List<DraftLine> lines;

  @override
  List<Object?> get props => [orderId, lines];
}

class DraftSendRequested extends DraftsEvent {
  const DraftSendRequested(
    this.orderId, {
    this.tender,
    this.applyStoreCredit = false,
    this.returnUrl,
    this.customerRef,
  });

  final String orderId;
  final String? tender;
  final bool applyStoreCredit;
  final String? returnUrl;
  final String? customerRef;

  @override
  List<Object?> get props => [orderId, tender, applyStoreCredit, returnUrl, customerRef];
}

class DraftCancelRequested extends DraftsEvent {
  const DraftCancelRequested(this.orderId);

  final String orderId;

  @override
  List<Object?> get props => [orderId];
}

class DraftOutcomeDismissed extends DraftsEvent {
  const DraftOutcomeDismissed();
}

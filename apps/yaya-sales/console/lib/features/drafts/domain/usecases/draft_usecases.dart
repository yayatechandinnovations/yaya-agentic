import '../../../orders/domain/entities/order_entities.dart';
import '../entities/draft_entities.dart';
import '../repositories/draft_repository.dart';

class CreateDraft {
  const CreateDraft(this._repo);
  final DraftRepository _repo;
  Future<Order> call({
    String? channel,
    String? regionId,
    String? customerRef,
    String? note,
    required List<DraftLine> lines,
  }) =>
      _repo.create(
          channel: channel,
          regionId: regionId,
          customerRef: customerRef,
          note: note,
          lines: lines);
}

class UpdateDraft {
  const UpdateDraft(this._repo);
  final DraftRepository _repo;
  Future<Order> call(String orderId, List<DraftLine> lines) => _repo.update(orderId, lines);
}

class SendDraft {
  const SendDraft(this._repo);
  final DraftRepository _repo;
  Future<SendOutcome> call(
    String orderId, {
    String? tender,
    bool applyStoreCredit = false,
    String? returnUrl,
    String? customerRef,
  }) =>
      _repo.send(orderId,
          tender: tender,
          applyStoreCredit: applyStoreCredit,
          returnUrl: returnUrl,
          customerRef: customerRef);
}

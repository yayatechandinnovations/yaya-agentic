import '../../../orders/domain/entities/order_entities.dart';
import '../entities/draft_entities.dart';

abstract class DraftRepository {
  Future<Order> create({
    String? channel,
    String? regionId,
    String? customerRef,
    String? note,
    required List<DraftLine> lines,
  });

  Future<Order> update(String orderId, List<DraftLine> lines);

  Future<SendOutcome> send(
    String orderId, {
    String? tender,
    bool applyStoreCredit = false,
    String? returnUrl,
    String? customerRef,
  });
}

import '../entities/rma_entities.dart';
import '../repositories/rma_repository.dart';

class RequestRma {
  const RequestRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String orderId, List<RmaLine> lines, String? reason) =>
      _repo.request(orderId, lines, reason);
}

class ListRmas {
  const ListRmas(this._repo);
  final RmaRepository _repo;
  Future<List<Rma>> call({String? status}) => _repo.list(status: status);
}

class GetRma {
  const GetRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id) => _repo.detail(id);
}

class AuthorizeRma {
  const AuthorizeRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id) => _repo.authorize(id);
}

class RejectRma {
  const RejectRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id, String? reason) => _repo.reject(id, reason);
}

class CancelRma {
  const CancelRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id) => _repo.cancel(id);
}

class SetReturnShipment {
  const SetReturnShipment(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id, ReturnShipmentInfo info) => _repo.setReturnShipment(id, info);
}

class ReceiveRma {
  const ReceiveRma(this._repo);
  final RmaRepository _repo;
  Future<Rma> call(String id, List<RmaDisposition> dispositions, String refundTo,
          int? amountMinor) =>
      _repo.receive(id, dispositions, refundTo, amountMinor);
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'courier_providers.dart';

/// Step 1 of courier onboarding (yaya-sales-design.md §8.9): the invite token
/// gates registration; the form is KYC-grade (legal name + legal address +
/// vehicle), so approval has something real to review.
class CourierRegistrationForm extends ConsumerStatefulWidget {
  const CourierRegistrationForm({super.key, this.inviteToken});

  final String? inviteToken;

  @override
  ConsumerState<CourierRegistrationForm> createState() => _CourierRegistrationFormState();
}

class _CourierRegistrationFormState extends ConsumerState<CourierRegistrationForm> {
  final _form = GlobalKey<FormState>();
  late final _invite = TextEditingController(text: widget.inviteToken ?? '');
  final _legalName = TextEditingController();
  final _phone = TextEditingController();
  final _line1 = TextEditingController();
  final _line2 = TextEditingController();
  final _city = TextEditingController();
  final _region = TextEditingController();
  final _postalCode = TextEditingController();
  final _country = TextEditingController();
  final _vehicleKind = TextEditingController();
  final _vehicleMake = TextEditingController();
  final _vehicleModel = TextEditingController();
  final _vehiclePlate = TextEditingController();

  @override
  void dispose() {
    for (final c in [
      _invite, _legalName, _phone, _line1, _line2, _city, _region,
      _postalCode, _country, _vehicleKind, _vehicleMake, _vehicleModel, _vehiclePlate,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    await ref.read(courierProvider.notifier).register(
          inviteToken: _invite.text.trim(),
          legalName: _legalName.text.trim(),
          phone: _phone.text.trim(),
          legalAddress: {
            'line1': _line1.text.trim(),
            'line2': _line2.text.trim().isEmpty ? null : _line2.text.trim(),
            'city': _city.text.trim(),
            'region': _region.text.trim(),
            'postalCode': _postalCode.text.trim(),
            'country': _country.text.trim(),
          },
          vehicle: {
            'kind': _vehicleKind.text.trim(),
            'make': _vehicleMake.text.trim(),
            'model': _vehicleModel.text.trim(),
            'plate': _vehiclePlate.text.trim(),
          },
        );
  }

  String? _required(String? v) =>
      (v == null || v.trim().isEmpty) ? 'Required' : null;

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(courierProvider);
    return Form(
      key: _form,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Become a delivery courier',
              style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          const Text('Enter the invite from the store, then your details. '
              'You will verify your phone and upload documents next.'),
          const SizedBox(height: 16),
          _field(_invite, 'Store invite token', validator: _required),
          const SizedBox(height: 20),
          Text('About you', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _field(_legalName, 'Legal name', validator: _required),
          _field(_phone, 'Phone (with country code)',
              validator: _required, keyboard: TextInputType.phone),
          const SizedBox(height: 20),
          Text('Legal address', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _field(_line1, 'Address line 1', validator: _required),
          _field(_line2, 'Address line 2 (optional)'),
          Row(children: [
            Expanded(child: _field(_city, 'City', validator: _required)),
            const SizedBox(width: 8),
            Expanded(child: _field(_region, 'State / region', validator: _required)),
          ]),
          Row(children: [
            Expanded(child: _field(_postalCode, 'Postal code', validator: _required)),
            const SizedBox(width: 8),
            Expanded(child: _field(_country, 'Country', validator: _required)),
          ]),
          const SizedBox(height: 20),
          Text('Your vehicle', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
                child: _field(_vehicleKind, 'Type (bike, car…)', validator: _required)),
            const SizedBox(width: 8),
            Expanded(child: _field(_vehiclePlate, 'Plate', validator: _required)),
          ]),
          Row(children: [
            Expanded(child: _field(_vehicleMake, 'Make', validator: _required)),
            const SizedBox(width: 8),
            Expanded(child: _field(_vehicleModel, 'Model', validator: _required)),
          ]),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: state.busy ? null : _submit,
            child: state.busy
                ? const SizedBox(
                    width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Register'),
          ),
          const SizedBox(height: 12),
          TextButton(
            onPressed: state.busy
                ? null
                : () => ref.read(courierProvider.notifier).showTokenEntry(),
            child: const Text('Already registered? Enter your access token'),
          ),
        ],
      ),
    );
  }

  Widget _field(TextEditingController controller, String label,
      {String? Function(String?)? validator, TextInputType? keyboard}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        keyboardType: keyboard,
        validator: validator,
        decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
      ),
    );
  }
}

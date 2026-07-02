import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_stripe/flutter_stripe.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/checkout_result.dart';
import '../chat_controller.dart';

/// Completes the checkout hand-off the framework produced. The framework is
/// never in the flow of funds (§11): it hands the client one of three modes and
/// the client finishes payment on the PSP's PCI-compliant surface.
///
/// - `hosted_url`     → open the PSP's hosted payment page.
/// - `payment_intent` → mount the PSP (Stripe) Payment Sheet with the supplied
///                       `clientSecret` + `publishableKey` (no card data ever
///                       touches yaya-agentic).
/// - `order_placed`   → terminal (COD / invoice); just confirm.
Future<void> handleCheckout(
  BuildContext context,
  WidgetRef ref,
  String handle,
  CheckoutResult result,
) async {
  final t = AppL10n.of(context);
  final messenger = ScaffoldMessenger.of(context);

  switch (result.mode) {
    case CheckoutMode.hostedUrl:
      final url = result.url;
      if (url == null || url.isEmpty) {
        messenger.showSnackBar(SnackBar(content: Text(t.couldNotOpenCheckout)));
        return;
      }
      messenger.showSnackBar(SnackBar(content: Text(t.openingCheckout)));
      final ok = await launchUrl(
        Uri.parse(url),
        mode: LaunchMode.externalApplication,
        webOnlyWindowName: '_blank',
      );
      if (!ok) messenger.showSnackBar(SnackBar(content: Text(t.couldNotOpenCheckout)));
      break;

    case CheckoutMode.paymentIntent:
      await _presentStripeSheet(context, ref, handle, result);
      break;

    case CheckoutMode.orderPlaced:
      messenger.showSnackBar(SnackBar(content: Text(t.orderPlacedTitle)));
      break;

    case CheckoutMode.unknown:
      break;
  }
}

Future<void> _presentStripeSheet(
  BuildContext context,
  WidgetRef ref,
  String handle,
  CheckoutResult result,
) async {
  final t = AppL10n.of(context);
  final messenger = ScaffoldMessenger.of(context);
  final controller = ref.read(chatControllerProvider(handle).notifier);
  final secret = result.clientSecret;
  final pk = result.publishableKey;
  if (secret == null || pk == null) {
    messenger.showSnackBar(SnackBar(content: Text(t.paymentFailed)));
    return;
  }
  try {
    // The publishable key is per-merchant and arrives in the payload, so set it
    // lazily here rather than globally at startup.
    Stripe.publishableKey = pk;
    await Stripe.instance.applySettings();
    await Stripe.instance.initPaymentSheet(
      paymentSheetParameters: SetupPaymentSheetParameters(
        paymentIntentClientSecret: secret,
        merchantDisplayName: 'Yaya',
      ),
    );
    await Stripe.instance.presentPaymentSheet();
    messenger.showSnackBar(SnackBar(content: Text(t.paymentComplete)));
    // The PSP webhook is the authoritative order/cart clear; refresh so the UI
    // catches up as soon as it lands.
    await controller.viewCart();
  } on StripeException catch (_) {
    // Includes user cancel — stay silent-ish; the cart is intact to retry.
  } catch (_) {
    messenger.showSnackBar(SnackBar(content: Text(t.paymentFailed)));
  }
}

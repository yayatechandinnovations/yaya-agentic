// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppL10nEn extends AppL10n {
  AppL10nEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Yaya Chat';

  @override
  String get onboardingVerifyTitle => 'Welcome — let\'s verify your number';

  @override
  String get onboardingCodeTitle => 'Enter the code we sent you';

  @override
  String get onboardingVerifyBlurb =>
      'We use your phone number to keep Yaya Chat human and spam-free.';

  @override
  String onboardingCodeBlurb(String phone) {
    return 'We sent a 6-digit code to $phone.';
  }

  @override
  String get onboardingNameTitle => 'Nice to meet you';

  @override
  String get onboardingNameBlurb =>
      'What should people call you? We\'ll set up your account.';

  @override
  String get fieldFullName => 'Full name';

  @override
  String get fieldPhone => 'Phone (e.g. +18095551234)';

  @override
  String get fieldCode => '6-digit code';

  @override
  String get sendCode => 'Send code';

  @override
  String get verify => 'Verify';

  @override
  String get changeNumber => 'Change number';

  @override
  String get directoryTitle => 'Your chats';

  @override
  String get noChatsYet => 'No chats yet';

  @override
  String get noChatsBlurb =>
      'Scan a business QR code to add it here, then tap to start chatting.';

  @override
  String get add => 'Add';

  @override
  String remove(String name) {
    return 'Remove $name';
  }

  @override
  String get guest => 'Guest';

  @override
  String get signInRequired => 'Sign-in required';

  @override
  String get verifyPhoneToChat => 'Verify your phone to chat as yourself';

  @override
  String get scanTitle => 'Scan a chat code';

  @override
  String get scanHint => 'Point your camera at a Yaya QR code';

  @override
  String get addByLink => 'Add by link';

  @override
  String get pasteLinkHint => 'Paste chat link or code';

  @override
  String get cancel => 'Cancel';

  @override
  String get addToMyChats => 'Add to my chats';

  @override
  String get chatAsGuest => 'Chat as guest';

  @override
  String get notAYayaCode => 'Not a Yaya chat code.';

  @override
  String get chatNotFound => 'That chat could not be found.';

  @override
  String get messageHint => 'Message…';

  @override
  String get signIn => 'Sign in';

  @override
  String get signedInNow => 'You\'re signed in now.';

  @override
  String get chatUnavailable => 'This chat is temporarily unavailable.';

  @override
  String get sessionExpired =>
      'This chat session expired. Reopen to start a new one.';

  @override
  String get retry => 'Retry';

  @override
  String get pasteToken => 'Paste access token';

  @override
  String get continueLabel => 'Continue';

  @override
  String get tooManyRequests => 'Too many requests — please try again shortly.';

  @override
  String get deepLinkCopied => 'Deep link copied';

  @override
  String get backToChats => 'Back to my chats';

  @override
  String get discoverTitle => 'Discover chats';

  @override
  String get discoverSearchHint => 'Search published chats';

  @override
  String get discoverEmpty => 'No published chats yet. Check back soon.';

  @override
  String get discoverNoResults => 'No chats match your search.';

  @override
  String get addToCart => 'Add to cart';

  @override
  String get viewProduct => 'View';

  @override
  String get cartTitle => 'Your cart';

  @override
  String get cartEmpty => 'Your cart is empty';

  @override
  String get subtotal => 'Subtotal';

  @override
  String itemsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count items',
      one: '1 item',
      zero: 'No items',
    );
    return '$_temp0';
  }

  @override
  String get checkout => 'Checkout';

  @override
  String get removeItem => 'Remove';

  @override
  String get outOfStock => 'Out of stock';

  @override
  String get lowStock => 'Low stock';

  @override
  String get preorder => 'Pre-order';

  @override
  String get loadMore => 'Load more';

  @override
  String resultsForQuery(int count, String query) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count results for \"$query\"',
      one: '1 result for \"$query\"',
    );
    return '$_temp0';
  }

  @override
  String get payNow => 'Pay now';

  @override
  String get orderPlacedTitle => 'Order placed';

  @override
  String orderRefLabel(String ref) {
    return 'Order $ref';
  }

  @override
  String get confirmAction => 'Confirm';

  @override
  String get keepShopping => 'Keep shopping';

  @override
  String get addedToCart => 'Added to cart';

  @override
  String get chooseOption => 'Choose an option';

  @override
  String get quantity => 'Quantity';

  @override
  String get continueToPayment => 'Continue to payment';

  @override
  String get paymentComplete => 'Payment complete';

  @override
  String get paymentFailed => 'Payment couldn\'t be completed.';

  @override
  String get openingCheckout => 'Opening secure checkout…';

  @override
  String get couldNotOpenCheckout => 'Couldn\'t open the checkout page.';

  @override
  String get cartUpdated => 'Cart updated';

  @override
  String get filters => 'Filters';

  @override
  String get account => 'Account';

  @override
  String get accountPhoneLabel => 'Phone number';

  @override
  String get accountNameLabel => 'Name';

  @override
  String get nameUpdated => 'Name updated';

  @override
  String get changePhoneNumber => 'Change phone number';

  @override
  String get deleteAccount => 'Delete account';

  @override
  String get signOut => 'Sign out';

  @override
  String get signOutConfirmTitle => 'Sign out everywhere?';

  @override
  String get signOutConfirmBody =>
      'This signs you out on all your devices. You\'ll need to verify your phone again to chat.';

  @override
  String get changePhoneNewNumberTitle => 'Enter your new number';

  @override
  String get changePhoneNewNumberBlurb =>
      'First we\'ll text a code to your current number to confirm it\'s you, then a code to the new one.';

  @override
  String get changePhoneOldCodeTitle => 'Confirm your current number';

  @override
  String get changePhoneOldCodeBlurb =>
      'Enter the 6-digit code we sent to your current number.';

  @override
  String get changePhoneNewCodeTitle => 'Confirm your new number';

  @override
  String changePhoneNewCodeBlurb(String phone) {
    return 'Enter the 6-digit code we sent to $phone.';
  }

  @override
  String get fieldNewPhone => 'New phone (e.g. +18095551234)';

  @override
  String get lostOldNumber =>
      'Lost access to your old number? Contact support.';

  @override
  String get resend => 'Resend code';

  @override
  String get codeResent => 'Code sent';

  @override
  String get phoneChanged => 'Phone number updated';

  @override
  String get next => 'Next';

  @override
  String get deleteAccountTitle => 'Delete your account?';

  @override
  String get deleteAccountWarning =>
      'This permanently deletes your account, your chats, and the businesses you\'ve added. This can\'t be undone. We\'ll text a code to confirm.';

  @override
  String get deleteConfirmTitle => 'Confirm deletion';

  @override
  String get deleteConfirmBlurb =>
      'Enter the 6-digit code we sent to your number to permanently delete your account.';

  @override
  String get deleteConfirmButton => 'Delete account';

  @override
  String get accountDeleted => 'Your account was deleted';

  @override
  String get resumePrompt => 'Continue where you left off?';

  @override
  String get continueChat => 'Continue';

  @override
  String get startNewChat => 'Start new';
}

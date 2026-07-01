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
}

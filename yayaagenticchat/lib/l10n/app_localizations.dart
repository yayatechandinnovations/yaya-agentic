import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_es.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppL10n
/// returned by `AppL10n.of(context)`.
///
/// Applications need to include `AppL10n.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppL10n.localizationsDelegates,
///   supportedLocales: AppL10n.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppL10n.supportedLocales
/// property.
abstract class AppL10n {
  AppL10n(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppL10n of(BuildContext context) {
    return Localizations.of<AppL10n>(context, AppL10n)!;
  }

  static const LocalizationsDelegate<AppL10n> delegate = _AppL10nDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('es'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Yaya Chat'**
  String get appTitle;

  /// No description provided for @onboardingVerifyTitle.
  ///
  /// In en, this message translates to:
  /// **'Welcome — let\'s verify your number'**
  String get onboardingVerifyTitle;

  /// No description provided for @onboardingCodeTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter the code we sent you'**
  String get onboardingCodeTitle;

  /// No description provided for @onboardingVerifyBlurb.
  ///
  /// In en, this message translates to:
  /// **'We use your phone number to keep Yaya Chat human and spam-free.'**
  String get onboardingVerifyBlurb;

  /// No description provided for @onboardingCodeBlurb.
  ///
  /// In en, this message translates to:
  /// **'We sent a 6-digit code to {phone}.'**
  String onboardingCodeBlurb(String phone);

  /// No description provided for @fieldFullName.
  ///
  /// In en, this message translates to:
  /// **'Full name'**
  String get fieldFullName;

  /// No description provided for @fieldPhone.
  ///
  /// In en, this message translates to:
  /// **'Phone (e.g. +18095551234)'**
  String get fieldPhone;

  /// No description provided for @fieldCode.
  ///
  /// In en, this message translates to:
  /// **'6-digit code'**
  String get fieldCode;

  /// No description provided for @sendCode.
  ///
  /// In en, this message translates to:
  /// **'Send code'**
  String get sendCode;

  /// No description provided for @verify.
  ///
  /// In en, this message translates to:
  /// **'Verify'**
  String get verify;

  /// No description provided for @changeNumber.
  ///
  /// In en, this message translates to:
  /// **'Change number'**
  String get changeNumber;

  /// No description provided for @directoryTitle.
  ///
  /// In en, this message translates to:
  /// **'Your chats'**
  String get directoryTitle;

  /// No description provided for @noChatsYet.
  ///
  /// In en, this message translates to:
  /// **'No chats yet'**
  String get noChatsYet;

  /// No description provided for @noChatsBlurb.
  ///
  /// In en, this message translates to:
  /// **'Scan a business QR code to add it here, then tap to start chatting.'**
  String get noChatsBlurb;

  /// No description provided for @add.
  ///
  /// In en, this message translates to:
  /// **'Add'**
  String get add;

  /// No description provided for @remove.
  ///
  /// In en, this message translates to:
  /// **'Remove {name}'**
  String remove(String name);

  /// No description provided for @guest.
  ///
  /// In en, this message translates to:
  /// **'Guest'**
  String get guest;

  /// No description provided for @signInRequired.
  ///
  /// In en, this message translates to:
  /// **'Sign-in required'**
  String get signInRequired;

  /// No description provided for @verifyPhoneToChat.
  ///
  /// In en, this message translates to:
  /// **'Verify your phone to chat as yourself'**
  String get verifyPhoneToChat;

  /// No description provided for @scanTitle.
  ///
  /// In en, this message translates to:
  /// **'Scan a chat code'**
  String get scanTitle;

  /// No description provided for @scanHint.
  ///
  /// In en, this message translates to:
  /// **'Point your camera at a Yaya QR code'**
  String get scanHint;

  /// No description provided for @addByLink.
  ///
  /// In en, this message translates to:
  /// **'Add by link'**
  String get addByLink;

  /// No description provided for @pasteLinkHint.
  ///
  /// In en, this message translates to:
  /// **'Paste chat link or code'**
  String get pasteLinkHint;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @addToMyChats.
  ///
  /// In en, this message translates to:
  /// **'Add to my chats'**
  String get addToMyChats;

  /// No description provided for @chatAsGuest.
  ///
  /// In en, this message translates to:
  /// **'Chat as guest'**
  String get chatAsGuest;

  /// No description provided for @notAYayaCode.
  ///
  /// In en, this message translates to:
  /// **'Not a Yaya chat code.'**
  String get notAYayaCode;

  /// No description provided for @chatNotFound.
  ///
  /// In en, this message translates to:
  /// **'That chat could not be found.'**
  String get chatNotFound;

  /// No description provided for @messageHint.
  ///
  /// In en, this message translates to:
  /// **'Message…'**
  String get messageHint;

  /// No description provided for @signIn.
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get signIn;

  /// No description provided for @signedInNow.
  ///
  /// In en, this message translates to:
  /// **'You\'re signed in now.'**
  String get signedInNow;

  /// No description provided for @chatUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This chat is temporarily unavailable.'**
  String get chatUnavailable;

  /// No description provided for @sessionExpired.
  ///
  /// In en, this message translates to:
  /// **'This chat session expired. Reopen to start a new one.'**
  String get sessionExpired;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @pasteToken.
  ///
  /// In en, this message translates to:
  /// **'Paste access token'**
  String get pasteToken;

  /// No description provided for @continueLabel.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueLabel;

  /// No description provided for @tooManyRequests.
  ///
  /// In en, this message translates to:
  /// **'Too many requests — please try again shortly.'**
  String get tooManyRequests;

  /// No description provided for @deepLinkCopied.
  ///
  /// In en, this message translates to:
  /// **'Deep link copied'**
  String get deepLinkCopied;

  /// No description provided for @backToChats.
  ///
  /// In en, this message translates to:
  /// **'Back to my chats'**
  String get backToChats;
}

class _AppL10nDelegate extends LocalizationsDelegate<AppL10n> {
  const _AppL10nDelegate();

  @override
  Future<AppL10n> load(Locale locale) {
    return SynchronousFuture<AppL10n>(lookupAppL10n(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'es'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppL10nDelegate old) => false;
}

AppL10n lookupAppL10n(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppL10nEn();
    case 'es':
      return AppL10nEs();
  }

  throw FlutterError(
    'AppL10n.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}

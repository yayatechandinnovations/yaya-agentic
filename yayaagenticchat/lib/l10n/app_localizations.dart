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

  /// No description provided for @onboardingNameTitle.
  ///
  /// In en, this message translates to:
  /// **'Nice to meet you'**
  String get onboardingNameTitle;

  /// No description provided for @onboardingNameBlurb.
  ///
  /// In en, this message translates to:
  /// **'What should people call you? We\'ll set up your account.'**
  String get onboardingNameBlurb;

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

  /// No description provided for @discoverTitle.
  ///
  /// In en, this message translates to:
  /// **'Discover chats'**
  String get discoverTitle;

  /// No description provided for @discoverSearchHint.
  ///
  /// In en, this message translates to:
  /// **'Search published chats'**
  String get discoverSearchHint;

  /// No description provided for @discoverEmpty.
  ///
  /// In en, this message translates to:
  /// **'No published chats yet. Check back soon.'**
  String get discoverEmpty;

  /// No description provided for @discoverNoResults.
  ///
  /// In en, this message translates to:
  /// **'No chats match your search.'**
  String get discoverNoResults;

  /// No description provided for @addToCart.
  ///
  /// In en, this message translates to:
  /// **'Add to cart'**
  String get addToCart;

  /// No description provided for @viewProduct.
  ///
  /// In en, this message translates to:
  /// **'View'**
  String get viewProduct;

  /// No description provided for @cartTitle.
  ///
  /// In en, this message translates to:
  /// **'Your cart'**
  String get cartTitle;

  /// No description provided for @cartEmpty.
  ///
  /// In en, this message translates to:
  /// **'Your cart is empty'**
  String get cartEmpty;

  /// No description provided for @subtotal.
  ///
  /// In en, this message translates to:
  /// **'Subtotal'**
  String get subtotal;

  /// No description provided for @itemsCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{No items} =1{1 item} other{{count} items}}'**
  String itemsCount(int count);

  /// No description provided for @checkout.
  ///
  /// In en, this message translates to:
  /// **'Checkout'**
  String get checkout;

  /// No description provided for @removeItem.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get removeItem;

  /// No description provided for @outOfStock.
  ///
  /// In en, this message translates to:
  /// **'Out of stock'**
  String get outOfStock;

  /// No description provided for @lowStock.
  ///
  /// In en, this message translates to:
  /// **'Low stock'**
  String get lowStock;

  /// No description provided for @preorder.
  ///
  /// In en, this message translates to:
  /// **'Pre-order'**
  String get preorder;

  /// No description provided for @loadMore.
  ///
  /// In en, this message translates to:
  /// **'Load more'**
  String get loadMore;

  /// No description provided for @resultsForQuery.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 result for \"{query}\"} other{{count} results for \"{query}\"}}'**
  String resultsForQuery(int count, String query);

  /// No description provided for @payNow.
  ///
  /// In en, this message translates to:
  /// **'Pay now'**
  String get payNow;

  /// No description provided for @orderPlacedTitle.
  ///
  /// In en, this message translates to:
  /// **'Order placed'**
  String get orderPlacedTitle;

  /// No description provided for @orderRefLabel.
  ///
  /// In en, this message translates to:
  /// **'Order {ref}'**
  String orderRefLabel(String ref);

  /// No description provided for @confirmAction.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get confirmAction;

  /// No description provided for @keepShopping.
  ///
  /// In en, this message translates to:
  /// **'Keep shopping'**
  String get keepShopping;

  /// No description provided for @addedToCart.
  ///
  /// In en, this message translates to:
  /// **'Added to cart'**
  String get addedToCart;

  /// No description provided for @chooseOption.
  ///
  /// In en, this message translates to:
  /// **'Choose an option'**
  String get chooseOption;

  /// No description provided for @quantity.
  ///
  /// In en, this message translates to:
  /// **'Quantity'**
  String get quantity;

  /// No description provided for @continueToPayment.
  ///
  /// In en, this message translates to:
  /// **'Continue to payment'**
  String get continueToPayment;

  /// No description provided for @paymentComplete.
  ///
  /// In en, this message translates to:
  /// **'Payment complete'**
  String get paymentComplete;

  /// No description provided for @paymentFailed.
  ///
  /// In en, this message translates to:
  /// **'Payment couldn\'t be completed.'**
  String get paymentFailed;

  /// No description provided for @openingCheckout.
  ///
  /// In en, this message translates to:
  /// **'Opening secure checkout…'**
  String get openingCheckout;

  /// No description provided for @couldNotOpenCheckout.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t open the checkout page.'**
  String get couldNotOpenCheckout;

  /// No description provided for @cartUpdated.
  ///
  /// In en, this message translates to:
  /// **'Cart updated'**
  String get cartUpdated;

  /// No description provided for @filters.
  ///
  /// In en, this message translates to:
  /// **'Filters'**
  String get filters;

  /// No description provided for @account.
  ///
  /// In en, this message translates to:
  /// **'Account'**
  String get account;

  /// No description provided for @accountPhoneLabel.
  ///
  /// In en, this message translates to:
  /// **'Phone number'**
  String get accountPhoneLabel;

  /// No description provided for @accountNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get accountNameLabel;

  /// No description provided for @nameUpdated.
  ///
  /// In en, this message translates to:
  /// **'Name updated'**
  String get nameUpdated;

  /// No description provided for @changePhoneNumber.
  ///
  /// In en, this message translates to:
  /// **'Change phone number'**
  String get changePhoneNumber;

  /// No description provided for @deleteAccount.
  ///
  /// In en, this message translates to:
  /// **'Delete account'**
  String get deleteAccount;

  /// No description provided for @signOut.
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get signOut;

  /// No description provided for @signOutConfirmTitle.
  ///
  /// In en, this message translates to:
  /// **'Sign out everywhere?'**
  String get signOutConfirmTitle;

  /// No description provided for @signOutConfirmBody.
  ///
  /// In en, this message translates to:
  /// **'This signs you out on all your devices. You\'ll need to verify your phone again to chat.'**
  String get signOutConfirmBody;

  /// No description provided for @changePhoneNewNumberTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter your new number'**
  String get changePhoneNewNumberTitle;

  /// No description provided for @changePhoneNewNumberBlurb.
  ///
  /// In en, this message translates to:
  /// **'First we\'ll text a code to your current number to confirm it\'s you, then a code to the new one.'**
  String get changePhoneNewNumberBlurb;

  /// No description provided for @changePhoneOldCodeTitle.
  ///
  /// In en, this message translates to:
  /// **'Confirm your current number'**
  String get changePhoneOldCodeTitle;

  /// No description provided for @changePhoneOldCodeBlurb.
  ///
  /// In en, this message translates to:
  /// **'Enter the 6-digit code we sent to your current number.'**
  String get changePhoneOldCodeBlurb;

  /// No description provided for @changePhoneNewCodeTitle.
  ///
  /// In en, this message translates to:
  /// **'Confirm your new number'**
  String get changePhoneNewCodeTitle;

  /// No description provided for @changePhoneNewCodeBlurb.
  ///
  /// In en, this message translates to:
  /// **'Enter the 6-digit code we sent to {phone}.'**
  String changePhoneNewCodeBlurb(String phone);

  /// No description provided for @fieldNewPhone.
  ///
  /// In en, this message translates to:
  /// **'New phone (e.g. +18095551234)'**
  String get fieldNewPhone;

  /// No description provided for @lostOldNumber.
  ///
  /// In en, this message translates to:
  /// **'Lost access to your old number? Contact support.'**
  String get lostOldNumber;

  /// No description provided for @resend.
  ///
  /// In en, this message translates to:
  /// **'Resend code'**
  String get resend;

  /// No description provided for @codeResent.
  ///
  /// In en, this message translates to:
  /// **'Code sent'**
  String get codeResent;

  /// No description provided for @phoneChanged.
  ///
  /// In en, this message translates to:
  /// **'Phone number updated'**
  String get phoneChanged;

  /// No description provided for @next.
  ///
  /// In en, this message translates to:
  /// **'Next'**
  String get next;

  /// No description provided for @deleteAccountTitle.
  ///
  /// In en, this message translates to:
  /// **'Delete your account?'**
  String get deleteAccountTitle;

  /// No description provided for @deleteAccountWarning.
  ///
  /// In en, this message translates to:
  /// **'This permanently deletes your account, your chats, and the businesses you\'ve added. This can\'t be undone. We\'ll text a code to confirm.'**
  String get deleteAccountWarning;

  /// No description provided for @deleteConfirmTitle.
  ///
  /// In en, this message translates to:
  /// **'Confirm deletion'**
  String get deleteConfirmTitle;

  /// No description provided for @deleteConfirmBlurb.
  ///
  /// In en, this message translates to:
  /// **'Enter the 6-digit code we sent to your number to permanently delete your account.'**
  String get deleteConfirmBlurb;

  /// No description provided for @deleteConfirmButton.
  ///
  /// In en, this message translates to:
  /// **'Delete account'**
  String get deleteConfirmButton;

  /// No description provided for @accountDeleted.
  ///
  /// In en, this message translates to:
  /// **'Your account was deleted'**
  String get accountDeleted;

  /// No description provided for @resumePrompt.
  ///
  /// In en, this message translates to:
  /// **'Continue where you left off?'**
  String get resumePrompt;

  /// No description provided for @continueChat.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueChat;

  /// No description provided for @startNewChat.
  ///
  /// In en, this message translates to:
  /// **'Start new'**
  String get startNewChat;

  /// No description provided for @ok.
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get ok;

  /// No description provided for @navChats.
  ///
  /// In en, this message translates to:
  /// **'Chats'**
  String get navChats;

  /// No description provided for @navDiscover.
  ///
  /// In en, this message translates to:
  /// **'Discover'**
  String get navDiscover;

  /// No description provided for @navAccount.
  ///
  /// In en, this message translates to:
  /// **'Account'**
  String get navAccount;

  /// No description provided for @lockTitle.
  ///
  /// In en, this message translates to:
  /// **'Welcome back'**
  String get lockTitle;

  /// No description provided for @lockTitleNamed.
  ///
  /// In en, this message translates to:
  /// **'Welcome back, {name}'**
  String lockTitleNamed(String name);

  /// No description provided for @lockBlurb.
  ///
  /// In en, this message translates to:
  /// **'Enter your PIN to unlock'**
  String get lockBlurb;

  /// No description provided for @lockUnlock.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get lockUnlock;

  /// No description provided for @lockForgotPin.
  ///
  /// In en, this message translates to:
  /// **'Forgot PIN? Verify by SMS'**
  String get lockForgotPin;

  /// No description provided for @pinTooShort.
  ///
  /// In en, this message translates to:
  /// **'The PIN must be 4–6 digits'**
  String get pinTooShort;

  /// No description provided for @pinMismatch.
  ///
  /// In en, this message translates to:
  /// **'The PINs don\'t match'**
  String get pinMismatch;

  /// No description provided for @pinSetupTitle.
  ///
  /// In en, this message translates to:
  /// **'Protect your account with a PIN'**
  String get pinSetupTitle;

  /// No description provided for @pinSetupBlurb.
  ///
  /// In en, this message translates to:
  /// **'Next time you open the app you\'ll just enter this PIN — no more SMS codes. You can change it anytime in Account → Security.'**
  String get pinSetupBlurb;

  /// No description provided for @pinSetupNewLabel.
  ///
  /// In en, this message translates to:
  /// **'New PIN'**
  String get pinSetupNewLabel;

  /// No description provided for @pinSetupConfirmLabel.
  ///
  /// In en, this message translates to:
  /// **'Confirm PIN'**
  String get pinSetupConfirmLabel;

  /// No description provided for @pinSetupSave.
  ///
  /// In en, this message translates to:
  /// **'Set PIN'**
  String get pinSetupSave;

  /// No description provided for @pinSetupSkip.
  ///
  /// In en, this message translates to:
  /// **'Not now'**
  String get pinSetupSkip;

  /// No description provided for @securitySection.
  ///
  /// In en, this message translates to:
  /// **'Security'**
  String get securitySection;

  /// No description provided for @securityPin.
  ///
  /// In en, this message translates to:
  /// **'App PIN'**
  String get securityPin;

  /// No description provided for @securityPinOn.
  ///
  /// In en, this message translates to:
  /// **'On — asked when the app opens'**
  String get securityPinOn;

  /// No description provided for @securityPinOff.
  ///
  /// In en, this message translates to:
  /// **'Off — set one to skip SMS codes'**
  String get securityPinOff;

  /// No description provided for @securityPinSetTitle.
  ///
  /// In en, this message translates to:
  /// **'Set your PIN'**
  String get securityPinSetTitle;

  /// No description provided for @securityPinChange.
  ///
  /// In en, this message translates to:
  /// **'Change PIN'**
  String get securityPinChange;

  /// No description provided for @securityPinSaved.
  ///
  /// In en, this message translates to:
  /// **'PIN saved'**
  String get securityPinSaved;

  /// No description provided for @securityPinRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove PIN'**
  String get securityPinRemove;

  /// No description provided for @securityPinRemoveBlurb.
  ///
  /// In en, this message translates to:
  /// **'Without a PIN this device stays signed in for longer, but anyone holding it can open your chats.'**
  String get securityPinRemoveBlurb;

  /// No description provided for @securityPinRemoved.
  ///
  /// In en, this message translates to:
  /// **'PIN removed'**
  String get securityPinRemoved;

  /// No description provided for @securityPinWrong.
  ///
  /// In en, this message translates to:
  /// **'That PIN is not right'**
  String get securityPinWrong;

  /// No description provided for @securityPinCurrentLabel.
  ///
  /// In en, this message translates to:
  /// **'Current PIN'**
  String get securityPinCurrentLabel;

  /// No description provided for @securityDevices.
  ///
  /// In en, this message translates to:
  /// **'Devices'**
  String get securityDevices;

  /// No description provided for @securityDevicesEmpty.
  ///
  /// In en, this message translates to:
  /// **'No devices yet'**
  String get securityDevicesEmpty;

  /// No description provided for @securityDeviceThis.
  ///
  /// In en, this message translates to:
  /// **'This device'**
  String get securityDeviceThis;

  /// No description provided for @securityDeviceUnnamed.
  ///
  /// In en, this message translates to:
  /// **'Unnamed device'**
  String get securityDeviceUnnamed;

  /// No description provided for @securityDeviceLastSeen.
  ///
  /// In en, this message translates to:
  /// **'Last active {date}'**
  String securityDeviceLastSeen(String date);

  /// No description provided for @securityDeviceAskPin.
  ///
  /// In en, this message translates to:
  /// **'Ask for PIN'**
  String get securityDeviceAskPin;

  /// No description provided for @securityDeviceRevoke.
  ///
  /// In en, this message translates to:
  /// **'Sign out this device'**
  String get securityDeviceRevoke;

  /// No description provided for @securityDeviceRevoked.
  ///
  /// In en, this message translates to:
  /// **'Device signed out'**
  String get securityDeviceRevoked;

  /// No description provided for @securityDeviceTrustTitle.
  ///
  /// In en, this message translates to:
  /// **'Turn off the PIN here?'**
  String get securityDeviceTrustTitle;

  /// No description provided for @securityDeviceTrustBlurb.
  ///
  /// In en, this message translates to:
  /// **'Enter your PIN to stop asking for it on this device. You can turn it back on anytime.'**
  String get securityDeviceTrustBlurb;

  /// No description provided for @securityLogoutDevice.
  ///
  /// In en, this message translates to:
  /// **'Log out on this device'**
  String get securityLogoutDevice;

  /// No description provided for @securityLogoutDeviceBlurb.
  ///
  /// In en, this message translates to:
  /// **'Your chats stay on your account — verify your number again to come back.'**
  String get securityLogoutDeviceBlurb;

  /// No description provided for @attachTooltip.
  ///
  /// In en, this message translates to:
  /// **'Attach'**
  String get attachTooltip;

  /// No description provided for @attachPhoto.
  ///
  /// In en, this message translates to:
  /// **'Photo'**
  String get attachPhoto;

  /// No description provided for @attachDocument.
  ///
  /// In en, this message translates to:
  /// **'Document (PDF)'**
  String get attachDocument;

  /// No description provided for @attachFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not upload'**
  String get attachFailed;

  /// No description provided for @voiceRecord.
  ///
  /// In en, this message translates to:
  /// **'Record a voice note'**
  String get voiceRecord;

  /// No description provided for @voiceStop.
  ///
  /// In en, this message translates to:
  /// **'Send voice note'**
  String get voiceStop;

  /// No description provided for @voiceDiscard.
  ///
  /// In en, this message translates to:
  /// **'Discard recording'**
  String get voiceDiscard;

  /// No description provided for @voiceMicDenied.
  ///
  /// In en, this message translates to:
  /// **'Microphone access is needed to record voice notes'**
  String get voiceMicDenied;

  /// No description provided for @voiceShowTranscript.
  ///
  /// In en, this message translates to:
  /// **'View transcript'**
  String get voiceShowTranscript;

  /// No description provided for @voiceHideTranscript.
  ///
  /// In en, this message translates to:
  /// **'Hide transcript'**
  String get voiceHideTranscript;

  /// No description provided for @receiptsTitle.
  ///
  /// In en, this message translates to:
  /// **'Receipts'**
  String get receiptsTitle;

  /// No description provided for @receiptsEmpty.
  ///
  /// In en, this message translates to:
  /// **'Tickets and receipts you receive in chats will appear here.'**
  String get receiptsEmpty;

  /// No description provided for @receiptsFallbackTitle.
  ///
  /// In en, this message translates to:
  /// **'Receipt'**
  String get receiptsFallbackTitle;

  /// No description provided for @navForum.
  ///
  /// In en, this message translates to:
  /// **'Forum'**
  String get navForum;

  /// No description provided for @forumTitle.
  ///
  /// In en, this message translates to:
  /// **'Forum'**
  String get forumTitle;

  /// No description provided for @forumNoChats.
  ///
  /// In en, this message translates to:
  /// **'Add a chat first — its community will show up here.'**
  String get forumNoChats;

  /// No description provided for @forumDisabled.
  ///
  /// In en, this message translates to:
  /// **'This chat doesn\'t have a community space yet.'**
  String get forumDisabled;

  /// No description provided for @forumEmpty.
  ///
  /// In en, this message translates to:
  /// **'No posts yet. Be the first to say something!'**
  String get forumEmpty;

  /// No description provided for @forumComposerHint.
  ///
  /// In en, this message translates to:
  /// **'Share something…'**
  String get forumComposerHint;

  /// No description provided for @forumReply.
  ///
  /// In en, this message translates to:
  /// **'Reply'**
  String get forumReply;

  /// No description provided for @forumReplyHint.
  ///
  /// In en, this message translates to:
  /// **'Write a reply…'**
  String get forumReplyHint;

  /// No description provided for @forumNoReplies.
  ///
  /// In en, this message translates to:
  /// **'No replies yet.'**
  String get forumNoReplies;

  /// No description provided for @forumEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get forumEdit;

  /// No description provided for @forumEditTitle.
  ///
  /// In en, this message translates to:
  /// **'Edit post'**
  String get forumEditTitle;

  /// No description provided for @forumDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get forumDelete;

  /// No description provided for @forumReport.
  ///
  /// In en, this message translates to:
  /// **'Report'**
  String get forumReport;

  /// No description provided for @forumReportTitle.
  ///
  /// In en, this message translates to:
  /// **'Report this post'**
  String get forumReportTitle;

  /// No description provided for @forumReported.
  ///
  /// In en, this message translates to:
  /// **'Thanks — the team will review it.'**
  String get forumReported;

  /// No description provided for @forumBlock.
  ///
  /// In en, this message translates to:
  /// **'Block member'**
  String get forumBlock;

  /// No description provided for @forumBlockTitle.
  ///
  /// In en, this message translates to:
  /// **'Block this member?'**
  String get forumBlockTitle;

  /// No description provided for @forumBlockBody.
  ///
  /// In en, this message translates to:
  /// **'You won\'t see their posts or replies in this forum anymore.'**
  String get forumBlockBody;

  /// No description provided for @forumBlocked.
  ///
  /// In en, this message translates to:
  /// **'Blocked — you won\'t see their posts.'**
  String get forumBlocked;

  /// No description provided for @forumBlockedMembers.
  ///
  /// In en, this message translates to:
  /// **'Blocked members'**
  String get forumBlockedMembers;

  /// No description provided for @forumUnblock.
  ///
  /// In en, this message translates to:
  /// **'Unblock'**
  String get forumUnblock;

  /// No description provided for @forumNoBlocked.
  ///
  /// In en, this message translates to:
  /// **'You haven\'t blocked anyone.'**
  String get forumNoBlocked;

  /// No description provided for @forumEdited.
  ///
  /// In en, this message translates to:
  /// **'edited'**
  String get forumEdited;

  /// No description provided for @forumJustNow.
  ///
  /// In en, this message translates to:
  /// **'now'**
  String get forumJustNow;

  /// No description provided for @confirmTitle.
  ///
  /// In en, this message translates to:
  /// **'Do you want to continue?'**
  String get confirmTitle;

  /// No description provided for @view.
  ///
  /// In en, this message translates to:
  /// **'View'**
  String get view;

  /// No description provided for @receiptDraftTitle.
  ///
  /// In en, this message translates to:
  /// **'Ticket in progress'**
  String get receiptDraftTitle;

  /// No description provided for @receiptDraftHint.
  ///
  /// In en, this message translates to:
  /// **'Tap to see it — keep adding to build your ticket'**
  String get receiptDraftHint;

  /// No description provided for @ownerMenu.
  ///
  /// In en, this message translates to:
  /// **'Menu'**
  String get ownerMenu;

  /// No description provided for @newMessages.
  ///
  /// In en, this message translates to:
  /// **'New messages'**
  String get newMessages;
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

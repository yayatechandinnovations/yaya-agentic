// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Spanish Castilian (`es`).
class AppL10nEs extends AppL10n {
  AppL10nEs([String locale = 'es']) : super(locale);

  @override
  String get appTitle => 'Yaya Chat';

  @override
  String get onboardingVerifyTitle => 'Bienvenido — verifiquemos tu número';

  @override
  String get onboardingCodeTitle => 'Ingresa el código que te enviamos';

  @override
  String get onboardingVerifyBlurb =>
      'Usamos tu número de teléfono para mantener Yaya Chat humano y sin spam.';

  @override
  String onboardingCodeBlurb(String phone) {
    return 'Enviamos un código de 6 dígitos a $phone.';
  }

  @override
  String get fieldFullName => 'Nombre completo';

  @override
  String get fieldPhone => 'Teléfono (ej. +18095551234)';

  @override
  String get fieldCode => 'Código de 6 dígitos';

  @override
  String get sendCode => 'Enviar código';

  @override
  String get verify => 'Verificar';

  @override
  String get changeNumber => 'Cambiar número';

  @override
  String get directoryTitle => 'Tus chats';

  @override
  String get noChatsYet => 'Aún no tienes chats';

  @override
  String get noChatsBlurb =>
      'Escanea el código QR de un negocio para agregarlo aquí y toca para empezar a chatear.';

  @override
  String get add => 'Agregar';

  @override
  String remove(String name) {
    return 'Eliminar $name';
  }

  @override
  String get guest => 'Invitado';

  @override
  String get signInRequired => 'Requiere inicio de sesión';

  @override
  String get verifyPhoneToChat => 'Verifica tu teléfono para chatear como tú';

  @override
  String get scanTitle => 'Escanea un código de chat';

  @override
  String get scanHint => 'Apunta tu cámara al código QR de Yaya';

  @override
  String get addByLink => 'Agregar con enlace';

  @override
  String get pasteLinkHint => 'Pega el enlace o código del chat';

  @override
  String get cancel => 'Cancelar';

  @override
  String get addToMyChats => 'Agregar a mis chats';

  @override
  String get chatAsGuest => 'Chatear como invitado';

  @override
  String get notAYayaCode => 'No es un código de chat de Yaya.';

  @override
  String get chatNotFound => 'No se pudo encontrar ese chat.';

  @override
  String get messageHint => 'Mensaje…';

  @override
  String get signIn => 'Iniciar sesión';

  @override
  String get signedInNow => 'Ya iniciaste sesión.';

  @override
  String get chatUnavailable => 'Este chat no está disponible temporalmente.';

  @override
  String get sessionExpired =>
      'Esta sesión de chat expiró. Vuelve a abrirla para empezar una nueva.';

  @override
  String get retry => 'Reintentar';

  @override
  String get pasteToken => 'Pega el token de acceso';

  @override
  String get continueLabel => 'Continuar';

  @override
  String get tooManyRequests =>
      'Demasiadas solicitudes — inténtalo de nuevo en un momento.';

  @override
  String get deepLinkCopied => 'Enlace copiado';

  @override
  String get backToChats => 'Volver a mis chats';

  @override
  String get discoverTitle => 'Descubrir chats';

  @override
  String get discoverSearchHint => 'Busca chats publicados';

  @override
  String get discoverEmpty => 'Aún no hay chats publicados. Vuelve pronto.';

  @override
  String get discoverNoResults => 'Ningún chat coincide con tu búsqueda.';
}

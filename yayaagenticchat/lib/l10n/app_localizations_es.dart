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
  String get onboardingNameTitle => 'Mucho gusto';

  @override
  String get onboardingNameBlurb =>
      '¿Cómo quieres que te llamen? Crearemos tu cuenta.';

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

  @override
  String get addToCart => 'Agregar al carrito';

  @override
  String get viewProduct => 'Ver';

  @override
  String get cartTitle => 'Tu carrito';

  @override
  String get cartEmpty => 'Tu carrito está vacío';

  @override
  String get subtotal => 'Subtotal';

  @override
  String itemsCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count artículos',
      one: '1 artículo',
      zero: 'Sin artículos',
    );
    return '$_temp0';
  }

  @override
  String get checkout => 'Pagar';

  @override
  String get removeItem => 'Quitar';

  @override
  String get outOfStock => 'Agotado';

  @override
  String get lowStock => 'Pocas unidades';

  @override
  String get preorder => 'Reservar';

  @override
  String get loadMore => 'Cargar más';

  @override
  String resultsForQuery(int count, String query) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count resultados para \"$query\"',
      one: '1 resultado para \"$query\"',
    );
    return '$_temp0';
  }

  @override
  String get payNow => 'Pagar ahora';

  @override
  String get orderPlacedTitle => 'Pedido realizado';

  @override
  String orderRefLabel(String ref) {
    return 'Pedido $ref';
  }

  @override
  String get confirmAction => 'Confirmar';

  @override
  String get keepShopping => 'Seguir comprando';

  @override
  String get addedToCart => 'Agregado al carrito';

  @override
  String get chooseOption => 'Elige una opción';

  @override
  String get quantity => 'Cantidad';

  @override
  String get continueToPayment => 'Continuar al pago';

  @override
  String get paymentComplete => 'Pago completado';

  @override
  String get paymentFailed => 'No se pudo completar el pago.';

  @override
  String get openingCheckout => 'Abriendo el pago seguro…';

  @override
  String get couldNotOpenCheckout => 'No se pudo abrir la página de pago.';

  @override
  String get cartUpdated => 'Carrito actualizado';

  @override
  String get filters => 'Filtros';

  @override
  String get account => 'Cuenta';

  @override
  String get accountPhoneLabel => 'Número de teléfono';

  @override
  String get accountNameLabel => 'Nombre';

  @override
  String get nameUpdated => 'Nombre actualizado';

  @override
  String get changePhoneNumber => 'Cambiar número de teléfono';

  @override
  String get deleteAccount => 'Eliminar cuenta';

  @override
  String get signOut => 'Cerrar sesión';

  @override
  String get signOutConfirmTitle => '¿Cerrar sesión en todos lados?';

  @override
  String get signOutConfirmBody =>
      'Esto cierra tu sesión en todos tus dispositivos. Tendrás que verificar tu teléfono de nuevo para chatear.';

  @override
  String get changePhoneNewNumberTitle => 'Ingresa tu nuevo número';

  @override
  String get changePhoneNewNumberBlurb =>
      'Primero enviaremos un código a tu número actual para confirmar que eres tú, y luego un código al nuevo.';

  @override
  String get changePhoneOldCodeTitle => 'Confirma tu número actual';

  @override
  String get changePhoneOldCodeBlurb =>
      'Ingresa el código de 6 dígitos que enviamos a tu número actual.';

  @override
  String get changePhoneNewCodeTitle => 'Confirma tu nuevo número';

  @override
  String changePhoneNewCodeBlurb(String phone) {
    return 'Ingresa el código de 6 dígitos que enviamos a $phone.';
  }

  @override
  String get fieldNewPhone => 'Nuevo teléfono (ej. +18095551234)';

  @override
  String get lostOldNumber =>
      '¿Perdiste el acceso a tu número anterior? Contacta a soporte.';

  @override
  String get resend => 'Reenviar código';

  @override
  String get codeResent => 'Código enviado';

  @override
  String get phoneChanged => 'Número de teléfono actualizado';

  @override
  String get next => 'Siguiente';

  @override
  String get deleteAccountTitle => '¿Eliminar tu cuenta?';

  @override
  String get deleteAccountWarning =>
      'Esto elimina permanentemente tu cuenta, tus chats y los negocios que agregaste. No se puede deshacer. Enviaremos un código para confirmar.';

  @override
  String get deleteConfirmTitle => 'Confirmar eliminación';

  @override
  String get deleteConfirmBlurb =>
      'Ingresa el código de 6 dígitos que enviamos a tu número para eliminar tu cuenta permanentemente.';

  @override
  String get deleteConfirmButton => 'Eliminar cuenta';

  @override
  String get accountDeleted => 'Tu cuenta fue eliminada';

  @override
  String get resumePrompt => '¿Continuar donde lo dejaste?';

  @override
  String get continueChat => 'Continuar';

  @override
  String get startNewChat => 'Empezar de nuevo';

  @override
  String get ok => 'Aceptar';

  @override
  String get navChats => 'Chats';

  @override
  String get navDiscover => 'Descubrir';

  @override
  String get navAccount => 'Cuenta';

  @override
  String get lockTitle => 'Bienvenido de nuevo';

  @override
  String lockTitleNamed(String name) {
    return 'Bienvenido de nuevo, $name';
  }

  @override
  String get lockBlurb => 'Ingresa tu PIN para desbloquear';

  @override
  String get lockUnlock => 'Desbloquear';

  @override
  String get lockForgotPin => '¿Olvidaste tu PIN? Verifica por SMS';

  @override
  String get pinTooShort => 'El PIN debe tener de 4 a 6 dígitos';

  @override
  String get pinMismatch => 'Los PIN no coinciden';

  @override
  String get pinSetupTitle => 'Protege tu cuenta con un PIN';

  @override
  String get pinSetupBlurb =>
      'La próxima vez que abras la app solo ingresarás este PIN — sin más códigos SMS. Puedes cambiarlo cuando quieras en Cuenta → Seguridad.';

  @override
  String get pinSetupNewLabel => 'Nuevo PIN';

  @override
  String get pinSetupConfirmLabel => 'Confirmar PIN';

  @override
  String get pinSetupSave => 'Establecer PIN';

  @override
  String get pinSetupSkip => 'Ahora no';

  @override
  String get securitySection => 'Seguridad';

  @override
  String get securityPin => 'PIN de la app';

  @override
  String get securityPinOn => 'Activado — se pide al abrir la app';

  @override
  String get securityPinOff =>
      'Desactivado — configúralo para evitar códigos SMS';

  @override
  String get securityPinSetTitle => 'Configura tu PIN';

  @override
  String get securityPinChange => 'Cambiar PIN';

  @override
  String get securityPinSaved => 'PIN guardado';

  @override
  String get securityPinRemove => 'Quitar PIN';

  @override
  String get securityPinRemoveBlurb =>
      'Sin PIN este dispositivo permanece conectado más tiempo, pero cualquiera que lo tenga puede abrir tus chats.';

  @override
  String get securityPinRemoved => 'PIN eliminado';

  @override
  String get securityPinWrong => 'Ese PIN no es correcto';

  @override
  String get securityPinCurrentLabel => 'PIN actual';

  @override
  String get securityDevices => 'Dispositivos';

  @override
  String get securityDevicesEmpty => 'Aún no hay dispositivos';

  @override
  String get securityDeviceThis => 'Este dispositivo';

  @override
  String get securityDeviceUnnamed => 'Dispositivo sin nombre';

  @override
  String securityDeviceLastSeen(String date) {
    return 'Última actividad $date';
  }

  @override
  String get securityDeviceAskPin => 'Pedir PIN';

  @override
  String get securityDeviceRevoke => 'Cerrar sesión en este dispositivo';

  @override
  String get securityDeviceRevoked => 'Sesión cerrada en el dispositivo';

  @override
  String get securityDeviceTrustTitle => '¿Desactivar el PIN aquí?';

  @override
  String get securityDeviceTrustBlurb =>
      'Ingresa tu PIN para dejar de pedirlo en este dispositivo. Puedes reactivarlo cuando quieras.';

  @override
  String get securityLogoutDevice => 'Cerrar sesión en este dispositivo';

  @override
  String get securityLogoutDeviceBlurb =>
      'Tus chats permanecen en tu cuenta — verifica tu número de nuevo para volver.';

  @override
  String get attachTooltip => 'Adjuntar';

  @override
  String get attachPhoto => 'Foto';

  @override
  String get attachDocument => 'Documento (PDF)';

  @override
  String get attachFailed => 'No se pudo subir';

  @override
  String get voiceRecord => 'Grabar una nota de voz';

  @override
  String get voiceStop => 'Enviar nota de voz';

  @override
  String get voiceDiscard => 'Descartar grabación';

  @override
  String get voiceMicDenied =>
      'Se necesita acceso al micrófono para grabar notas de voz';

  @override
  String get voiceShowTranscript => 'Ver transcripción';

  @override
  String get voiceHideTranscript => 'Ocultar transcripción';

  @override
  String get receiptsTitle => 'Recibos';

  @override
  String get receiptsEmpty =>
      'Los boletos y recibos que recibas en tus chats aparecerán aquí.';

  @override
  String get receiptsFallbackTitle => 'Recibo';

  @override
  String get navForum => 'Foro';

  @override
  String get forumTitle => 'Foro';

  @override
  String get forumNoChats =>
      'Agrega un chat primero — su comunidad aparecerá aquí.';

  @override
  String get forumDisabled => 'Este chat aún no tiene un espacio de comunidad.';

  @override
  String get forumEmpty =>
      'Aún no hay publicaciones. ¡Sé el primero en decir algo!';

  @override
  String get forumComposerHint => 'Comparte algo…';

  @override
  String get forumReply => 'Responder';

  @override
  String get forumReplyHint => 'Escribe una respuesta…';

  @override
  String get forumNoReplies => 'Aún no hay respuestas.';

  @override
  String get forumEdit => 'Editar';

  @override
  String get forumEditTitle => 'Editar publicación';

  @override
  String get forumDelete => 'Eliminar';

  @override
  String get forumReport => 'Reportar';

  @override
  String get forumReportTitle => 'Reportar esta publicación';

  @override
  String get forumReported => 'Gracias — el equipo lo revisará.';

  @override
  String get forumEdited => 'editado';

  @override
  String get forumJustNow => 'ahora';
}

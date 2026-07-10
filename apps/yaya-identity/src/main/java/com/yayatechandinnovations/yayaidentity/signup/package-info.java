/**
 * Public self-registration + invitation acceptance (design §5.2, §10.2). The only
 * unauthenticated write surface of yaya-identity: a stranger registers an org by
 * proving control of a phone number (OTP via {@link
 * com.yayatechandinnovations.yayaidentity.signup.OtpChannel}); an invitee redeems
 * an admin-issued token. Registration is OFF by default
 * ({@code yaya.identity.signup.enabled}) — invitation acceptance is always on,
 * because invitations are admin-initiated.
 */
package com.yayatechandinnovations.yayaidentity.signup;

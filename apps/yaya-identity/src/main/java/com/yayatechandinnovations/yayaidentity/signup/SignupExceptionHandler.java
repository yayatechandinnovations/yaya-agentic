package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.InvitationService;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = SignupController.class)
public class SignupExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class,
            CredentialService.WeakPasswordException.class,
            SignupService.InvalidOtpException.class,
            SignupService.InvalidSignupException.class,
            InvitationService.InvalidInvitationException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(SignupService.SignupDisabledException.class)
    ResponseEntity<Map<String, String>> disabled(RuntimeException e) {
        return error(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler({UserService.EmailAlreadyInUseException.class,
            SignupService.PhoneAlreadyInUseException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(SignupService.SignupExpiredException.class)
    ResponseEntity<Map<String, String>> gone(RuntimeException e) {
        return error(HttpStatus.GONE, e);
    }

    @ExceptionHandler(SignupService.RateLimitedException.class)
    ResponseEntity<Map<String, String>> rateLimited(RuntimeException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e);
    }

    @ExceptionHandler(SignupService.OtpDeliveryException.class)
    ResponseEntity<Map<String, String>> delivery(RuntimeException e) {
        return error(HttpStatus.BAD_GATEWAY, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}

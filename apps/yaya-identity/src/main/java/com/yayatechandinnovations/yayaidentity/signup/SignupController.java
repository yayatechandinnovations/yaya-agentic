package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.organizations.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The unauthenticated onboarding surface. Register/verify/resend are gated by
 * {@code yaya.identity.signup.enabled}; invitation acceptance is always on
 * because the token in hand IS the authorization (admin-issued, hashed at rest,
 * single-use, TTL'd).
 */
@RestController
@RequestMapping("/signup")
public class SignupController {

    private final SignupService signup;
    private final InvitationService invitations;

    public SignupController(SignupService signup, InvitationService invitations) {
        this.signup = signup;
        this.invitations = invitations;
    }

    public record RegisterDto(String orgName, String displayName, String email,
                              String phone, String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterDto body, HttpServletRequest request) {
        var result = signup.register(body.orgName(), body.displayName(), body.email(),
                body.phone(), body.password(), clientIp(request));
        return registerResponse(result);
    }

    public record VerifyDto(String signupId, String code) {}

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody VerifyDto body) {
        var result = signup.verify(body.signupId(), body.code());
        return Map.of(
                "userId", result.userId(),
                "orgId", result.orgId(),
                "orgName", result.orgName());
    }

    public record ResendDto(String signupId) {}

    @PostMapping("/resend")
    public Map<String, Object> resend(@RequestBody ResendDto body, HttpServletRequest request) {
        return registerResponse(signup.resend(body.signupId(), clientIp(request)));
    }

    public record AcceptInviteDto(String token, String displayName, String password) {}

    @PostMapping("/invitations/accept")
    public Map<String, Object> acceptInvitation(@RequestBody AcceptInviteDto body) {
        var result = invitations.accept(body.token(), body.displayName(), body.password());
        return Map.of("userId", result.user().value(), "orgId", result.org().value());
    }

    private static Map<String, Object> registerResponse(SignupService.RegisterResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("signupId", result.signupId());
        response.put("otpSent", true);
        result.devOtp().ifPresent(code -> response.put("devOtp", code));
        return response;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}

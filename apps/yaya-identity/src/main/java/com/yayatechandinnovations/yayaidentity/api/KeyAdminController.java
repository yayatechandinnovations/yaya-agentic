package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.platform.keys.KeyRing;
import com.yayatechandinnovations.yayaidentity.platform.keys.SigningKey;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Signing-key operations (design §11, §13; US-62). Rotation is make-before-break:
 * a new ACTIVE key is minted and the previous one moves to RETIRING (still in
 * JWKS until its tokens expire), so verification never has a gap. Token-protected
 * by the resource-server chain — a platform-operator action.
 */
@RestController
@RequestMapping("/v1/identity/admin/keys")
public class KeyAdminController {

    private final KeyRing keyRing;

    public KeyAdminController(KeyRing keyRing) {
        this.keyRing = keyRing;
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotate() {
        SigningKey active = keyRing.rotate();
        return Map.of("activeKid", active.kid().value());
    }
}

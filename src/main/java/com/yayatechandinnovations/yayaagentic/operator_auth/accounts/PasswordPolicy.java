package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.stereotype.Component;

/**
 * Server-side password policy gate. Mirrors the client-side strength meter
 * in UI-UX §5.5 but is the authoritative check — the UI is UX, not
 * security.
 */
@Component
public class PasswordPolicy {

    private final int minLength;
    private final int classesRequired;

    public PasswordPolicy(YayaAgenticProperties props) {
        YayaAgenticProperties.UserAccounts ua = props.userAccounts();
        YayaAgenticProperties.UserAccounts.Password p =
                (ua == null || ua.password() == null)
                        ? YayaAgenticProperties.UserAccounts.Password.defaults()
                        : ua.password();
        this.minLength = p.minLength();
        this.classesRequired = p.classesRequired();
    }

    /** @throws PasswordPolicyException if the password fails any requirement */
    public void requireValid(char[] password) {
        if (password == null || password.length < minLength) {
            throw new PasswordPolicyException("password must be at least " + minLength + " characters");
        }
        int classes = countClasses(password);
        if (classes < classesRequired) {
            throw new PasswordPolicyException("password must mix at least "
                    + classesRequired + " character classes (lowercase, uppercase, digits, symbols)");
        }
    }

    private static int countClasses(char[] pw) {
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (char c : pw) {
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else symbol = true;
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
    }
}

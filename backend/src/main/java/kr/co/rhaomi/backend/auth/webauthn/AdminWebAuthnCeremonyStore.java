package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import org.springframework.stereotype.Component;

@Component
class AdminWebAuthnCeremonyStore {

    private static final String REGISTRATION_SESSION_ATTRIBUTE =
            AdminWebAuthnService.class.getName() + ".REGISTRATION";
    private static final String AUTHENTICATION_SESSION_ATTRIBUTE =
            AdminWebAuthnService.class.getName() + ".AUTHENTICATION";

    void storeRegistration(HttpSession session, Serializable ceremony) {
        store(session, REGISTRATION_SESSION_ATTRIBUTE, ceremony);
    }

    void storeAuthentication(HttpSession session, Serializable ceremony) {
        store(session, AUTHENTICATION_SESSION_ATTRIBUTE, ceremony);
    }

    <T> T consumeRegistration(HttpSession session, Class<T> ceremonyType) {
        return consume(session, REGISTRATION_SESSION_ATTRIBUTE, ceremonyType);
    }

    <T> T consumeAuthentication(HttpSession session, Class<T> ceremonyType) {
        return consume(session, AUTHENTICATION_SESSION_ATTRIBUTE, ceremonyType);
    }

    private static void store(HttpSession session, String attributeName, Serializable ceremony) {
        if (session == null) {
            throw new WebAuthnVerificationException();
        }
        synchronized (session) {
            session.setAttribute(attributeName, ceremony);
        }
    }

    private static <T> T consume(
            HttpSession session, String attributeName, Class<T> ceremonyType) {
        if (session == null) {
            throw new WebAuthnVerificationException();
        }
        Object stored;
        synchronized (session) {
            stored = session.getAttribute(attributeName);
            session.removeAttribute(attributeName);
        }
        if (!ceremonyType.isInstance(stored)) {
            throw new WebAuthnVerificationException();
        }
        return ceremonyType.cast(stored);
    }
}

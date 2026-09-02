package kr.co.rhaomi.backend.content;

import org.hibernate.exception.ConstraintViolationException;

public final class ContentPersistenceErrors {

    private ContentPersistenceErrors() {}

    public static boolean isConstraint(Throwable failure, String expectedName) {
        var current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && expectedName.equals(violation.getConstraintName())) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }
}

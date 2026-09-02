package kr.co.rhaomi.backend.content;

public final class ContentFields {

    public static final String SLUG_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    private ContentFields() {}

    public static String required(String value) {
        return value.strip();
    }

    public static String optional(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}

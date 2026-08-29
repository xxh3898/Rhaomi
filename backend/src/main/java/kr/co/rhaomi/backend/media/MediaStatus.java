package kr.co.rhaomi.backend.media;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum MediaStatus {
    ACTIVE("active"),
    ARCHIVED("archived");

    private final String value;

    MediaStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MediaStatus from(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "active" -> ACTIVE;
            case "archived" -> ARCHIVED;
            default -> throw new IllegalArgumentException("Unsupported media status");
        };
    }
}

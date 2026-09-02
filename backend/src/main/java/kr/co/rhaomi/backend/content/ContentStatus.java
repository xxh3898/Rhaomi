package kr.co.rhaomi.backend.content;

import java.util.Arrays;

public enum ContentStatus {
    DRAFT("draft"),
    PUBLISHED("published"),
    ARCHIVED("archived");

    private final String apiValue;

    ContentStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static ContentStatus fromApiValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.apiValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 콘텐츠 상태입니다."));
    }
}

package kr.co.rhaomi.backend.media;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MediaStatusConverter implements AttributeConverter<MediaStatus, String> {

    @Override
    public String convertToDatabaseColumn(MediaStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public MediaStatus convertToEntityAttribute(String dbData) {
        return MediaStatus.from(dbData);
    }
}

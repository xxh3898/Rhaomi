package kr.co.rhaomi.backend.content;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ContentStatusConverter implements AttributeConverter<ContentStatus, String> {

    @Override
    public String convertToDatabaseColumn(ContentStatus attribute) {
        return attribute == null ? null : attribute.apiValue();
    }

    @Override
    public ContentStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ContentStatus.fromApiValue(dbData);
    }
}

package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.enums.TransactionType;

import javax.persistence.Converter;
import javax.persistence.AttributeConverter;

@Converter(autoApply = true)
public class TransactionTypeConverter implements AttributeConverter<TransactionType, String> {

    @Override
    public String convertToDatabaseColumn(TransactionType attribute) {
        return attribute.name().toLowerCase(); // store as lowercase
    }

    @Override
    public TransactionType convertToEntityAttribute(String dbData) {
        return TransactionType.valueOf(dbData.toUpperCase()); // read from lowercase DB
    }
}

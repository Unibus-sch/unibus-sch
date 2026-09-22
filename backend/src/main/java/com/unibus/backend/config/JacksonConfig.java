package com.unibus.backend.config;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter EDGE_TIMESTAMP_FORMAT = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .appendOffset("+HH:MM", "+00:00")
        .toFormatter();

    @Bean
    JsonMapperBuilderCustomizer edgeTimestampCustomizer() {
        SimpleModule module = new SimpleModule("edge-timestamp-compatibility");
        module.addSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer());
        return builder -> builder.addModule(module);
    }

    static String formatTimestamp(OffsetDateTime value) {
        return EDGE_TIMESTAMP_FORMAT.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static final class OffsetDateTimeSerializer extends StdSerializer<OffsetDateTime> {

        private OffsetDateTimeSerializer() {
            super(OffsetDateTime.class);
        }

        @Override
        public void serialize(
            OffsetDateTime value,
            JsonGenerator generator,
            SerializationContext context
        ) throws JacksonException {
            generator.writeString(formatTimestamp(value));
        }
    }
}

package dev.fschat.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared, pre-configured Jackson {@link ObjectMapper} for all fschat JSON
 * (de)serialization, plus convenience wrappers that surface failures as an
 * unchecked {@link JsonException}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code NON_NULL} inclusion &mdash; null record components are omitted,
 *       keeping the wire compact and friendly to the hand-built JSON the Vim
 *       plugin consumes.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} &mdash; forward compatibility,
 *       so an older peer tolerates fields added by a newer one.</li>
 * </ul>
 *
 * <p>Timestamps are carried as ISO-8601 {@code String}s (not {@code java.time}
 * types) so we need no extra Jackson datatype module.
 */
public final class Json {

    /** The shared, thread-safe mapper. Safe to reuse across threads once built. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private Json() {
    }

    /** Serialize {@code value} to a JSON string. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonException("failed to serialize " + value.getClass().getName(), e);
        }
    }

    /** Deserialize {@code json} into an instance of {@code type}. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("failed to deserialize " + type.getName(), e);
        }
    }

    /** Unchecked wrapper for Jackson processing failures. */
    public static final class JsonException extends RuntimeException {
        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

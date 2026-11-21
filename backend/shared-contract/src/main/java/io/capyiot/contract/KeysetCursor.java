package io.capyiot.contract;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record KeysetCursor(Instant at, String id) {
    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2100-01-01T00:00:00Z");
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static KeysetCursor parse(String value) {
        if (value.length() > 160) throw new IllegalArgumentException("Invalid cursor");
        var fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\n", -1);
        if (fields.length != 2 || !fields[1].matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid cursor");
        try {
            Instant time = Instant.parse(fields[0]);
            if (time.isBefore(EARLIEST) || !time.isBefore(LATEST)) throw new IllegalArgumentException("Invalid cursor");
            return new KeysetCursor(time, fields[1]);
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid cursor", invalid);
        }
    }
}

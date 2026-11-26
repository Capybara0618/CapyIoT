package io.capyiot.contract.internal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class ContractChecks {
    private ContractChecks() {}

    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid resource identifier");
        return value;
    }

    static String optionalId(String value) { return value == null ? null : id(value); }

    static String text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid contract text");
        return value;
    }

    static String permission(String value) {
        if (value == null || !value.matches("[a-z]+(?:\\.[a-z]+){1,4}"))
            throw new IllegalArgumentException("Invalid permission code");
        return value;
    }

    static <T> List<T> batch(Collection<T> values, boolean allowEmpty) {
        if (values == null || values.size() > 100 || (!allowEmpty && values.isEmpty()))
            throw new IllegalArgumentException("Batch size must be within 1–100");
        return List.copyOf(values);
    }

    static List<String> ids(Collection<String> values, boolean allowEmpty) {
        List<String> copy = batch(values, allowEmpty);
        copy.forEach(ContractChecks::id);
        if (copy.stream().distinct().count() != copy.size())
            throw new IllegalArgumentException("Duplicate resource identifier");
        return copy;
    }

    static long version(long value) {
        if (value < 0) throw new IllegalArgumentException("Invalid version");
        return value;
    }

    static <T> T required(T value) { return Objects.requireNonNull(value, "Missing contract value"); }
}

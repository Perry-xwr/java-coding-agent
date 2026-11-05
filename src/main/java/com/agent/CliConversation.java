package com.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A managed conversation wraps the existing complete, versioned CLI snapshot. */
public record CliConversation(int version, String id, String title, String createdAt,
                              String updatedAt, CliSessionSnapshot snapshot) {
    public static final int CURRENT_VERSION = 1;

    public CliConversation {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("Unsupported conversation version");
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
        validateTitle(title);
        if (Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
            throw new IllegalArgumentException("Invalid conversation timestamps");
        }
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    public static void validateTitle(String title) {
        if (title == null || title.isBlank() || title.length() > 120
                || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Title must be 1-120 characters without control characters");
        }
    }
}

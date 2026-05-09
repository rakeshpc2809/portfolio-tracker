package com.oreki.cas_injector.core.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base class for all immutable domain events.
 * Uses Jackson polymorphic type info to preserve type during Kafka serialization.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public abstract class DomainEvent {
    @Builder.Default
    private final String eventId = UUID.randomUUID().toString();
    private String aggregateId; // The ID of the entity this event applies to
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    private long version; // For optimistic concurrency and ordering
}

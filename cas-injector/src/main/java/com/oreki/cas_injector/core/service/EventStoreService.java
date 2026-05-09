package com.oreki.cas_injector.core.service;

import com.oreki.cas_injector.core.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreService {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void publish(DomainEvent event) {
        log.info("Publishing {} locally: {}", event.getClass().getSimpleName(), event.getEventId());
        applicationEventPublisher.publishEvent(event);
    }
}

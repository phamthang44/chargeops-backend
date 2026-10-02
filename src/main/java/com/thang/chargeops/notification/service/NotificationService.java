package com.thang.chargeops.notification.service;

import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final AppNotificationRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createTicketNotice(UUID recipientId, String eventKey, String title,
                                   String body, String actionUrl, Instant createdAt) {
        repository.saveAndFlush(AppNotification.ticket(recipientId, eventKey, title, body, actionUrl, createdAt));
    }
}

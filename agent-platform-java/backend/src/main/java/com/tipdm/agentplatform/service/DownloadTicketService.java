package com.tipdm.agentplatform.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 短时、单次使用的产物下载票据。 */
@Service
public class DownloadTicketService {

    private static final long TTL_SECONDS = 60;
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String issue(String userId, UUID agentId, String sessionId, String path, String fileName) {
        purgeExpired();
        String token = UUID.randomUUID().toString();
        tickets.put(token, new Ticket(userId, agentId, sessionId, path, fileName,
                Instant.now().plusSeconds(TTL_SECONDS)));
        return token;
    }

    public Optional<Ticket> consume(String token, UUID agentId, String sessionId) {
        Ticket ticket = tickets.remove(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())
                || !ticket.agentId().equals(agentId)
                || !ticket.sessionId().equals(sessionId)) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public record Ticket(String userId, UUID agentId, String sessionId, String path,
                         String fileName, Instant expiresAt) {
    }
}

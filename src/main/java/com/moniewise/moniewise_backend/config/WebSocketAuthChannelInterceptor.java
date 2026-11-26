package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.UserService;
import io.jsonwebtoken.ExpiredJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthChannelInterceptor.class);
    private static final long AUTH_CACHE_TTL_MS = 2 * 60 * 1000L;

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final ConcurrentHashMap<String, CachedAuth> authCache = new ConcurrentHashMap<>();

    private record CachedAuth(UsernamePasswordAuthenticationToken auth, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    public WebSocketAuthChannelInterceptor(JwtUtil jwtUtil,
                                           UserService userService,
                                           AuthSessionService authSessionService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authSessionService = authSessionService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if ((StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                || StompCommand.SEND.equals(accessor.getCommand()))
                && accessor.getUser() == null) {
            throw new AccessDeniedException("WebSocket authentication is required.");
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = resolveBearerToken(accessor);
        if (token == null || token.isBlank()) {
            throw new AccessDeniedException("Missing WebSocket authorization token.");
        }

        try {
            String email = jwtUtil.extractEmail(token);
            String sessionId = jwtUtil.extractSessionId(token);
            if (email == null || sessionId == null) {
                throw new AccessDeniedException("Invalid WebSocket authorization token.");
            }

            String cacheKey = email + ":" + sessionId;
            CachedAuth cached = authCache.get(cacheKey);
            if (cached != null && cached.isValid()) {
                accessor.setUser(cached.auth());
                return;
            }

            User user = userService.findByEmail(email);
            UserDetails userDetails = userService.buildUserDetails(user);
            boolean sessionActive = authSessionService.isSessionActive(email, sessionId);

            if (!jwtUtil.validateToken(token, userDetails) || !sessionActive) {
                authCache.remove(cacheKey);
                throw new AccessDeniedException("Invalid WebSocket session.");
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);

            authCache.put(cacheKey, new CachedAuth(authentication,
                    System.currentTimeMillis() + AUTH_CACHE_TTL_MS));
            evictExpiredEntries();
        } catch (ExpiredJwtException e) {
            log.warn("Expired JWT rejected for WebSocket connection");
            throw new AccessDeniedException("Expired WebSocket authorization token.", e);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Invalid JWT rejected for WebSocket connection: {}", e.getMessage());
            throw new AccessDeniedException("Invalid WebSocket authorization token.", e);
        }
    }

    private void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        authCache.entrySet().removeIf(e -> now >= e.getValue().expiresAt());
    }

    private String resolveBearerToken(StompHeaderAccessor accessor) {
        String header = firstNativeHeader(accessor, "Authorization");
        if (header == null) {
            header = firstNativeHeader(accessor, "authorization");
        }
        if (header == null) {
            header = firstNativeHeader(accessor, "access_token");
        }
        if (header == null) {
            header = firstNativeHeader(accessor, "token");
        }

        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        String value = accessor.getFirstNativeHeader(name);
        return value == null ? null : value.trim();
    }
}

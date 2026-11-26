package com.moniewise.moniewise_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.io.IOException;

@Component
public class QuietWebSocketHandshakeHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(QuietWebSocketHandshakeHandler.class);

    @Override
    protected void handleInvalidUpgradeHeader(ServerHttpRequest request,
                                              ServerHttpResponse response) throws IOException {
        log.debug("Rejected non-WebSocket request to {} because Upgrade header was {}",
                request.getURI().getPath(),
                request.getHeaders().getUpgrade());
        response.setStatusCode(HttpStatus.BAD_REQUEST);
    }

    @Override
    protected void handleInvalidConnectHeader(ServerHttpRequest request,
                                              ServerHttpResponse response) throws IOException {
        log.debug("Rejected invalid WebSocket handshake to {} because Connection header was {}",
                request.getURI().getPath(),
                request.getHeaders().getConnection());
        response.setStatusCode(HttpStatus.BAD_REQUEST);
    }
}

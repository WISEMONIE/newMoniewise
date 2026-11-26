package com.moniewise.moniewise_backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InsufficientFundsException extends RuntimeException {

    private final Map<String, Object> details;
    
    public InsufficientFundsException(String message) {
        this(message, Map.of());
    }

    public InsufficientFundsException(String message, Map<String, Object> details) {
        super(message);
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}

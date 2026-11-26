package com.moniewise.moniewise_backend.service;

class DeadTokenException extends RuntimeException {
    DeadTokenException(String token) {
        super(token);
    }
}

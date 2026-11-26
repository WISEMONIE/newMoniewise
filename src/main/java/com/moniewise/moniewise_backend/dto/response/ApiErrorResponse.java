package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResponse {
    private int status;           // HTTP status code (e.g., 404, 400, 500)
    private String error;         // HTTP error reason phrase (e.g., Not Found, Bad Request)
    private String message;       // Developer-provided error message
    private String path;          // The request path (/envelopes/999)
    private LocalDateTime timestamp; // When the error occurred
}

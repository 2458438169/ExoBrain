package com.kama.jchatmind.auth;

import com.kama.jchatmind.model.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handle(AuthException error) {
        var response = ResponseEntity.status(error.getStatus()).header("Cache-Control", "no-store");
        if (error.getStatus() == 429) response.header("Retry-After", "300");
        return response.body(ApiResponse.error(error.getStatus(), error.getMessage()));
    }
}

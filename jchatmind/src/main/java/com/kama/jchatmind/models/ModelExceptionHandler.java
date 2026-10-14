package com.kama.jchatmind.models;

import com.kama.jchatmind.model.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ModelExceptionHandler {
    @ExceptionHandler(ModelException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ModelException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.error(exception.getStatus(), exception.getMessage()));
    }
}

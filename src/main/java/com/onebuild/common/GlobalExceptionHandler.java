package com.onebuild.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(new ApiResponse<>(e.getHttpStatus(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("参数错误");
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("数据约束冲突", e);
        return ResponseEntity.status(409).body(ApiResponse.error(409, "数据冲突，请检查唯一约束或引用关系"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, "资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "服务器内部错误"));
    }

}

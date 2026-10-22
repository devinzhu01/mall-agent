package com.mall.agent.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误体 {code, message}:请求参数/格式问题返回 400,其余异常交给 Spring Boot 默认处理。 */
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        String message = e instanceof IllegalArgumentException ? e.getMessage() : "请求格式不正确";
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", message));
    }

}

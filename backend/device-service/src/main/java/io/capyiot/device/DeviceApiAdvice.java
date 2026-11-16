package io.capyiot.device;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class DeviceApiAdvice {
    @ExceptionHandler(DeviceProblem.class)
    ResponseEntity<?> problem(DeviceProblem error) {
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage(),
                "details", Map.of(), "traceId", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(DuplicateKeyException error) {
        return problem(DeviceProblem.conflict("设备编号或名称已存在"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception error) {
        return problem(DeviceProblem.badRequest("请求字段格式不正确，请检查后重试"));
    }
}

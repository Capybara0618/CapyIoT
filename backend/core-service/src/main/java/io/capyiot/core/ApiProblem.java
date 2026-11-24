package io.capyiot.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;

public final class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;
    public ApiProblem(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiProblem missing() { return new ApiProblem(404,"RESOURCE_NOT_FOUND","资源不存在或不可见"); }
    public static ApiProblem forbidden() { return new ApiProblem(403,"PERMISSION_DENIED","当前授权不允许此操作"); }
    public static ApiProblem invalid(String message) { return new ApiProblem(400,"VALIDATION_ERROR",message); }
    public static ApiProblem conflict(String message) { return new ApiProblem(409,"VERSION_CONFLICT",message); }
}

@RestControllerAdvice
class ProblemHandler {
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<?> problem(ApiProblem problem, HttpServletRequest request) {
        return ResponseEntity.status(problem.status()).body(Map.of("code",problem.code(),"message",problem.getMessage(),
                "details",Map.of(),"traceId",trace(request)));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error, HttpServletRequest request) {
        return problem(ApiProblem.invalid("请求参数不符合接口约定"),request);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(HttpServletRequest request) {
        return problem(new ApiProblem(409,"DUPLICATE_RESOURCE","当前有效记录已存在"),request);
    }
    static String trace(HttpServletRequest request) {
        Object existing=request.getAttribute("capyiot.trace");
        if(existing!=null) return existing.toString();
        String id=UUID.randomUUID().toString(); request.setAttribute("capyiot.trace",id); return id;
    }
}

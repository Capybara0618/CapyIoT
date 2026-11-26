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
    public static void requireReason(String reason){if(reason==null||reason.isBlank()||reason.length()>1000)throw invalid("必须提供 1–1000 字的原因");}
}

@RestControllerAdvice
class ProblemHandler {
    @ExceptionHandler(io.capyiot.service.ServiceFailure.class)
    ResponseEntity<?> dependency(io.capyiot.service.ServiceFailure error,HttpServletRequest request){return problem(new ApiProblem(error.status(),error.code(),"依赖服务暂不可用或无权执行"),request);}
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
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> unavailable(HttpServletRequest request) {
        return problem(new ApiProblem(503,"BUSINESS_STORE_UNAVAILABLE","业务数据暂时不可用，请稍后重试"),request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error,HttpServletRequest request) {
        System.getLogger(ProblemHandler.class.getName()).log(System.Logger.Level.ERROR,"Request {0} failed: {1}",trace(request),error.getClass().getSimpleName());
        return problem(new ApiProblem(500,"INTERNAL_ERROR","操作未完成，请保留追踪编号联系维护人员"),request);
    }
    static String trace(HttpServletRequest request) {
        Object existing=request.getAttribute("capyiot.trace");
        if(existing!=null) return existing.toString();
        String id=UUID.randomUUID().toString(); request.setAttribute("capyiot.trace",id); return id;
    }
}

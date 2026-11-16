package io.capyiot.workflow;

import java.util.Map;
import org.springframework.http.HttpStatus;

final class WorkOrderProblem extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Map<String, Object> details;

    WorkOrderProblem(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    static WorkOrderProblem conflict(String code, String message) {
        return new WorkOrderProblem(HttpStatus.CONFLICT, code, message, Map.of());
    }

    static WorkOrderProblem invalid(String message) {
        return new WorkOrderProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, Map.of());
    }

    static WorkOrderProblem missing(String message) {
        return new WorkOrderProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message, Map.of());
    }

    static WorkOrderProblem forbidden(String message) {
        return new WorkOrderProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", message, Map.of());
    }
}

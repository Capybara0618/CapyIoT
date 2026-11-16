package io.capyiot.workflow;

import static io.capyiot.workflow.WorkOrderModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class WorkOrderActions {
    private WorkOrderActions() {}

    static boolean needsAlarm(Action action) {
        return action == Action.VERIFY_RECOVERY || action == Action.CLOSE || action == Action.REOPEN;
    }

    static Status next(Status state, Action action) {
        return switch (action) {
            case ASSIGN -> state == Status.OPEN ? Status.ASSIGNED : null;
            case REASSIGN -> state == Status.ASSIGNED || state == Status.IN_PROGRESS ? state : null;
            case START_PROCESSING -> state == Status.ASSIGNED ? Status.IN_PROGRESS : null;
            case ADD_NOTE -> state == Status.IN_PROGRESS || state == Status.VERIFYING ? state : null;
            case COMPLETE_PROCESSING -> state == Status.IN_PROGRESS ? Status.VERIFYING : null;
            case VERIFY_RECOVERY -> state == Status.VERIFYING ? state : null;
            case RETURN_TO_PROCESSING -> state == Status.VERIFYING ? Status.IN_PROGRESS : null;
            case CLOSE -> state == Status.VERIFYING ? Status.CLOSED : null;
            case REOPEN -> state == Status.CLOSED ? Status.OPEN : null;
        };
    }

    static void check(Actor actor, Order order, Transition request) {
        if (order.version() != request.expectedVersion()) {
            throw new WorkOrderProblem(HttpStatus.CONFLICT, "WORKORDER_VERSION_CONFLICT", "工单已更新，请刷新后重试",
                    Map.of("currentVersion", order.version()));
        }
        Action action = request.action();
        if (next(order.status(), action) == null) {
            throw new WorkOrderProblem(HttpStatus.CONFLICT, "WORKORDER_ILLEGAL_TRANSITION", "当前状态不允许此操作",
                    Map.of("currentStatus", order.status(), "action", action));
        }
        if (!permitted(actor, order, action)) throw WorkOrderProblem.forbidden("只有处理人或管理员可以执行此操作");
        if (switch (action) {
            case ADD_NOTE, COMPLETE_PROCESSING, VERIFY_RECOVERY, RETURN_TO_PROCESSING, REOPEN -> true;
            default -> false;
        } && (request.remark() == null || request.remark().isBlank())) {
            throw WorkOrderProblem.invalid("请填写处置说明");
        }
    }

    static List<Action> available(Actor actor, Order order) {
        var actions = new ArrayList<Action>();
        for (Action action : Action.values()) {
            if (next(order.status(), action) == null || !permitted(actor, order, action)) continue;
            if (action == Action.CLOSE && !order.recoveryVerified()) continue;
            if (action == Action.VERIFY_RECOVERY && (!order.source().recovered() || order.recoveryVerified())) continue;
            if (action == Action.REOPEN && (order.source().recovered() || order.source().parentAlarmId() != null)) continue;
            actions.add(action);
        }
        return actions;
    }

    static String assignee(Actor actor, String requested) {
        String candidate = requested == null || requested.isBlank() ? actor.username() : requested.strip();
        if (!List.of("admin", "operator").contains(candidate)) {
            throw WorkOrderProblem.invalid("请选择有效的演示账号");
        }
        if (!actor.admin() && !actor.username().equals(candidate)) {
            throw WorkOrderProblem.forbidden("运维人员只能领取给自己");
        }
        return candidate;
    }

    private static boolean permitted(Actor actor, Order order, Action action) {
        if (actor.admin()) return true;
        return switch (action) {
            case ASSIGN -> true;
            case REASSIGN, REOPEN -> false;
            default -> actor.username().equals(order.assignee());
        };
    }
}

package io.capyiot.core.workorder;

import static io.capyiot.core.workorder.WorkOrderModels.*;
import io.capyiot.contract.ApiResponse;
import io.capyiot.core.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="capyiot.workorders.enabled",havingValue="true")
class WorkOrderController {
    private final WorkOrderService orders;private final PlatformService platform;
    WorkOrderController(WorkOrderService orders,PlatformService platform){this.orders=orders;this.platform=platform;}
    private <T> ApiResponse<T> ok(T body,HttpServletRequest request){return ApiResponse.ok(body,request.getRequestId());}
    private String requestId(HttpServletRequest request){String id=request.getHeader("X-Request-Id");return id==null?request.getRequestId():id;}
    @GetMapping("/workorders") ApiResponse<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String deviceId,@RequestParam(required=false) String incidentId,
        @RequestParam(required=false) Status status,@RequestParam(required=false) Priority priority,@RequestParam(required=false) String assignedRepairerId,@RequestParam(required=false) String designatedAcceptorId,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){return ok(orders.list(platform.current(jwt),deviceId,incidentId,status==null?null:status.name(),priority==null?null:priority.name(),assignedRepairerId,designatedAcceptorId,from,to,page,size),req);}
    @PostMapping("/workorders/validate") ApiResponse<?> validate(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Create body,HttpServletRequest req){return ok(orders.validateDraft(platform.current(jwt),body),req);}
    @PostMapping("/workorders") ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body,HttpServletRequest req){io.capyiot.core.platform.AgentDelegation.verifyBody(req);var result=orders.create(platform.current(jwt),key,requestId(req),body);return ResponseEntity.status(result.path("created").asBoolean()?201:200).body(ok(result,req));}
    @GetMapping("/workorders/commands/{key}") ApiResponse<?> receipt(@AuthenticationPrincipal Jwt jwt,@PathVariable String key,HttpServletRequest req){
        if(!(req.getAttribute("capyiot.delegation") instanceof io.capyiot.core.platform.AgentDelegation.Binding binding))throw io.capyiot.core.platform.AgentDelegation.denied();
        return ok(orders.commandReceipt(platform.current(jwt),binding),req);
    }
    @GetMapping("/workorders/{id}") ApiResponse<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,HttpServletRequest req){return ok(orders.detail(platform.current(jwt),id),req);}
    @PostMapping("/workorders/{id}/assign") ApiResponse<?> assign(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Assign body,HttpServletRequest req){return ok(orders.assign(platform.current(jwt),key,id,body,false),req);}
    @PostMapping("/workorders/{id}/reassign") ApiResponse<?> reassign(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Assign body,HttpServletRequest req){return ok(orders.assign(platform.current(jwt),key,id,body,true),req);}
    @PostMapping("/workorders/{id}/accept") ApiResponse<?> accept(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Version body,HttpServletRequest req){return ok(orders.accept(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/decline") ApiResponse<?> decline(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body,HttpServletRequest req){return ok(orders.decline(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/start") ApiResponse<?> start(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Version body,HttpServletRequest req){return ok(orders.start(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/repair-records") ApiResponse<?> repair(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Repair body,HttpServletRequest req){return ok(orders.repair(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/submit-completion") ApiResponse<?> submit(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Completion body,HttpServletRequest req){return ok(orders.submit(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/acceptance") ApiResponse<?> acceptance(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Acceptance body,HttpServletRequest req){return ok(orders.acceptance(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/cancel") ApiResponse<?> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body,HttpServletRequest req){return ok(orders.cancel(platform.current(jwt),key,id,body),req);}
    @PostMapping("/workorders/{id}/reopen") ApiResponse<?> reopen(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason body,HttpServletRequest req){return ok(orders.reopen(platform.current(jwt),key,requestId(req),id,body),req);}
    @GetMapping("/devices/{deviceId}/maintenance-history") ApiResponse<?> maintenance(@AuthenticationPrincipal Jwt jwt,@PathVariable String deviceId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){return ok(orders.maintenance(platform.current(jwt),deviceId,from,to,page,size),req);}
}

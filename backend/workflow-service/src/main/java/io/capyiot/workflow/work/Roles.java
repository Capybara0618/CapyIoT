package io.capyiot.workflow.work;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Roles {
  private Roles() {}

  private static final Set<String> READ =
      Set.of(
          "device.read",
          "topology.read",
          "event.read",
          "anomaly.read",
          "incident.read",
          "workorder.read",
          "agent.use",
          "agent.draft.prepare",
          "agent.draft.confirm");
  public static final Map<String, Set<String>> PERMISSIONS =
      Map.of(
          "SYS_ADMIN",
              Set.of(
                  "platform.user.read",
                  "platform.user.manage",
                  "platform.grant.assign",
                  "platform.grant.revoke",
                  "platform.audit.read"),
          "OPS_SUPERVISOR",
              plus(
                  READ,
                  Set.of(
                      "device.manage",
                      "topology.manage",
                      "platform.audit.read",
                      "anomaly.dismiss",
                      "incident.review",
                      "incident.priority.change",
                      "incident.close",
                      "workorder.create",
                      "workorder.assign",
                      "workorder.reassign",
                      "workorder.cancel",
                      "workorder.reopen")),
          "OPS_OPERATOR", plus(READ, Set.of("workorder.create")),
          "REPAIR_TECH",
              Set.of(
                  "device.read",
                  "topology.read",
                  "anomaly.read",
                  "incident.read",
                  "workorder.read",
                  "event.read",
                  "agent.use",
                  "workorder.repair.accept",
                  "workorder.repair.start",
                  "workorder.repair.record",
                  "workorder.repair.submit"),
          "ACCEPTANCE_STAFF",
              Set.of(
                  "device.read",
                  "topology.read",
                  "anomaly.read",
                  "incident.read",
                  "workorder.read",
                  "event.read",
                  "agent.use",
                  "workorder.acceptance.pass",
                  "workorder.acceptance.reject"));

  private static Set<String> plus(Set<String> first, Set<String> second) {
    return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
  }
}

package io.capyiot.workflow.work;

import io.capyiot.service.ServiceFailure;
import io.capyiot.service.ServiceKeyRing;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
class ServiceKeysController {
  private final ObjectProvider<ServiceKeyRing> keys;

  ServiceKeysController(ObjectProvider<ServiceKeyRing> keys) {
    this.keys = keys;
  }

  @GetMapping("/.well-known/service-jwks.json")
  Map<String, Object> jwks() {
    var ring = keys.getIfAvailable();
    if (ring == null) throw ServiceFailure.unavailable("SERVICE_KEYS_UNAVAILABLE", null, false);
    return ring.publicJwks();
  }
}

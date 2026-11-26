package io.capyiot.ingestion;

import io.capyiot.service.*;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
class ServiceKeysController {
    private final ObjectProvider<ServiceKeyRing> keys;
    ServiceKeysController(ObjectProvider<ServiceKeyRing> keys){this.keys=keys;}
    @GetMapping("/.well-known/service-jwks.json") Map<String,Object> jwks(){var ring=keys.getIfAvailable();if(ring==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);return ring.publicJwks();}
}

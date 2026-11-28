package io.capyiot.contract;

import java.nio.file.Files;
import java.nio.file.Path;

/** Pod-local drain marker, written by preStop; never an authorization or distributed lock. */
public final class RuntimeGate {
    private RuntimeGate() {}
    public static boolean draining() {
        return Files.exists(Path.of(System.getenv().getOrDefault("CAPYIOT_DRAIN_FILE", "/tmp/capyiot-draining")));
    }
}

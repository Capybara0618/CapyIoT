package io.capyiot.platform;

import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.PlatformApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonnelEligibilityTest {
    @Test void currentPersonnelFactsNeverRequireADeviceOrWorkOrderCallback() {
        var db=mock(JdbcTemplate.class);var assets=mock(AssetScopeClient.class);
        var service=new PlatformService(db,mock(TransactionTemplate.class),assets,mock(PlatformEvents.class));
        var actor=new AuthorizationContext("u","u","u",1,"s",List.of(new AuthorizationContext.Grant("g","OPS_SUPERVISOR","PARK","PARK-A",List.of(),null)));
        var device=new DeviceApi.Resource("IPC-1","PARK-A","AREA-1","IPC-1","IPC","Camera","IN_SERVICE",List.of("AREA-1"),0);
        // Empty local personnel facts are an authoritative empty candidate page, not a downstream fallback.
        var result=service.eligibility(actor,new PlatformApi.EligibilityRequest(List.of(),PlatformApi.PersonnelRole.REPAIR_TECH,List.of(device)));
        assertThat(result.candidates()).isEmpty();assertThat(result.nextCursor()).isNull();verifyNoInteractions(assets);
    }
}

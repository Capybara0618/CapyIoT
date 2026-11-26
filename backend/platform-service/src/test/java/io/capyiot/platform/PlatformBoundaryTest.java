package io.capyiot.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.PlatformApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformBoundaryTest {
    AuthorizationContext admin(){return new AuthorizationContext("admin","admin","Admin",1,"session",List.of(new AuthorizationContext.Grant("g","SYS_ADMIN","NONE",null,List.of(),null)));}
    @Test void identityLookupNeverCallsResourceAuthority() {
        var db=mock(JdbcTemplate.class);var assets=mock(AssetScopeClient.class);
        var service=new PlatformService(db,mock(TransactionTemplate.class),assets,mock(PlatformEvents.class));
        when(db.queryForList(startsWith("SELECT u.*"),any(Object[].class))).thenReturn(List.of(Map.of("authorization_version",1L,"login_name","u","display_name","User")));
        var token=Jwt.withTokenValue("token").header("alg","RS256").subject("u").id("jti").claim("sid","s").claim("authz_ver",1L).build();
        assertThat(service.current(token).userId()).isEqualTo("u");verifyNoInteractions(assets);
    }
    @Test void resourceDependencyFailureIsNotAnEmptySuccessfulGrant() {
        var db=mock(JdbcTemplate.class);var assets=mock(AssetScopeClient.class);
        var service=new PlatformService(db,mock(TransactionTemplate.class),assets,mock(PlatformEvents.class));
        when(assets.validate("CAMPUS-A",List.of())).thenThrow(io.capyiot.service.ServiceFailure.unavailable("DEPENDENCY_UNAVAILABLE",null,false));
        assertThatThrownBy(()->service.grant(admin(),"user-1",new PlatformService.GrantCommand("OPS_OPERATOR","PARK","CAMPUS-A",List.of(),null)))
                .isInstanceOf(io.capyiot.service.ServiceFailure.class);
        verifyNoInteractions(db);
    }
    @Test void grantDeviceValidationRunsBeforeCommandTransactionAndMutation() {
        var service=mock(PlatformService.class);var journal=mock(PlatformJournal.class);var actor=admin();
        var token=Jwt.withTokenValue("token").header("alg","RS256").subject("admin").build();
        var body=new PlatformService.GrantCommand("OPS_OPERATOR","PARK","CAMPUS-A",List.of(),null);
        var input=Map.of("userId","u","body",body);
        when(service.current(token)).thenReturn(actor);when(journal.replay("key",actor.userId(),"grant.create",input)).thenReturn(java.util.Optional.empty());
        when(journal.execute(eq("key"),eq(actor.userId()),eq("grant.create"),eq(input),any())).thenReturn(new ObjectMapper().createObjectNode());
        var provider=new StaticListableBeanFactory().getBeanProvider(io.capyiot.service.ServiceKeyRing.class);
        var controller=new PlatformController(service,journal,provider);
        controller.grant(token,"key","u",body,new org.springframework.mock.web.MockHttpServletRequest());
        var order=inOrder(service,journal);order.verify(service).current(token);order.verify(journal).replay("key",actor.userId(),"grant.create",input);
        order.verify(service).validateGrant(body);order.verify(journal).execute(eq("key"),eq(actor.userId()),eq("grant.create"),eq(input),any());
    }
    @Test void knownReceiptDoesNotRequireDeviceAvailabilityAgain() {
        var service=mock(PlatformService.class);var journal=mock(PlatformJournal.class);var actor=admin();
        var token=Jwt.withTokenValue("token").header("alg","RS256").subject("admin").build();
        var body=new PlatformService.GrantCommand("OPS_OPERATOR","PARK","CAMPUS-A",List.of(),null);
        when(service.current(token)).thenReturn(actor);when(journal.replay(eq("key"),eq(actor.userId()),eq("grant.create"),any())).thenReturn(java.util.Optional.of(new ObjectMapper().createObjectNode()));
        var controller=new PlatformController(service,journal,new StaticListableBeanFactory().getBeanProvider(io.capyiot.service.ServiceKeyRing.class));
        controller.grant(token,"key","u",body,new org.springframework.mock.web.MockHttpServletRequest());
        verify(service,never()).validateGrant(any());verify(journal,never()).execute(any(),any(),any(),any(),any());
    }
    @Test void legacyBridgeExpiresAndIsDisabledUnlessExplicitlyEnabled() {
        String key="a".repeat(32);
        assertThatThrownBy(()->new LegacyWindow(false,Instant.now().plusSeconds(60),key).verify(key)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->new LegacyWindow(true,Instant.EPOCH,key)).isInstanceOf(IllegalArgumentException.class);
        var window=new LegacyWindow(true,Instant.now().plusSeconds(60),key);
        assertThatThrownBy(()->window.verify("wrong")).isInstanceOf(ApiProblem.class);window.verify(key);
    }
    @Test void platformOnlyGrantCannotReadBusinessAssets() {
        assertThatThrownBy(()->admin().require("device.read")).isInstanceOf(ApiProblem.class);
        var actor=new AuthorizationContext("u","u","u",1,"s",List.of(new AuthorizationContext.Grant("g","OPS_OPERATOR","AREA_SUBTREE","PARK-A",List.of("AREA-A"),null)));
        assertThat(actor.require("device.read").getFirst().rootAreaIds()).containsExactly("AREA-A");
        assertThatThrownBy(()->actor.requirePlatform("platform.grant.assign")).isInstanceOf(ApiProblem.class);
    }
    @Test void resourceHttpClientRefusesCallsWhileALocalTransactionIsHeld() {
        var transport=mock(io.capyiot.service.InternalHttpClient.class);var resources=new HttpAssetScopeClient(transport);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->resources.validate("CAMPUS-A",List.of())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(transport);}
        finally{org.springframework.transaction.support.TransactionSynchronizationManager.clear();}
    }
}

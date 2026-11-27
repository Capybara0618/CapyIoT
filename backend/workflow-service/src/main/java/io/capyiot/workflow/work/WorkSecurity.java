package io.capyiot.workflow.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class WorkSecurity {
  @Bean
  SecurityFilterChain workSecurityFilterChain(
      HttpSecurity http,
      WorkIdentity platform,
      ObjectMapper json,
      ObjectProvider<ServiceTokenVerifier> tokens)
      throws Exception {
    var internal =
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            try {
              String path = HttpPaths.canonical(req.getRequestURI(), req.getContextPath());
              HttpServletRequest current = req;
              if (path.startsWith("/internal/v1/")) {
                var operation =
                    Arrays.stream(InternalEndpoint.values())
                        .filter(
                            e ->
                                e.target() == ServiceId.WORKFLOW
                                    && e.matches(req.getMethod(), path))
                        .findFirst()
                        .orElseThrow(() -> ServiceFailure.denied(403, "SERVICE_SCOPE_DENIED"));
                if (req.getQueryString() != null)
                  throw ServiceFailure.denied(400, "CONTRACT_QUERY_INVALID");
                if (req.getHeader(InternalEndpoint.SERVICE_AUTHORIZATION) == null)
                  throw ServiceFailure.denied(401, "SERVICE_TOKEN_INVALID");
                if (req.getContentLengthLong() > JdkServiceTransport.MAX_RESPONSE_BYTES)
                  throw ServiceFailure.denied(413, "CONTRACT_BODY_TOO_LARGE");
                var budget =
                    CallBudget.receive(
                        req.getHeader(InternalEndpoint.DEADLINE_HEADER),
                        Duration.ofSeconds(10),
                        Clock.systemUTC());
                byte[] body =
                    req.getInputStream().readNBytes(JdkServiceTransport.MAX_RESPONSE_BYTES + 1);
                budget.check();
                if (body.length > JdkServiceTransport.MAX_RESPONSE_BYTES)
                  throw ServiceFailure.denied(413, "CONTRACT_BODY_TOO_LARGE");
                String auth = req.getHeader("Authorization");
                var actor = auth == null ? null : ActorCredential.bearer(auth);
                var verifier = tokens.getIfAvailable();
                if (verifier == null)
                  throw ServiceFailure.unavailable(
                      "SERVICE_AUTH_UNAVAILABLE", ServiceId.WORKFLOW, false);
                var principal =
                    verifier.verify(
                        operation,
                        req.getMethod(),
                        path,
                        req.getHeader(InternalEndpoint.SERVICE_AUTHORIZATION),
                        actor,
                        body,
                        req.getHeader(InternalEndpoint.DEADLINE_HEADER),
                        budget);
                req.setAttribute("capyiot.call-budget", budget);
                req.setAttribute("capyiot.internal.principal", principal);
                current = new BodyRequest(req, body);
              } else if (path.equals("/internal") || path.startsWith("/internal/")) {
                throw ApiProblem.forbidden();
              }
              if (req.getMethod().equals("POST") && path.equals("/api/v1/workorders")) {
                byte[] command =
                    req.getInputStream().readNBytes(JdkServiceTransport.MAX_RESPONSE_BYTES + 1);
                if (command.length > JdkServiceTransport.MAX_RESPONSE_BYTES)
                  throw ServiceFailure.denied(413, "CONTRACT_BODY_TOO_LARGE");
                req.setAttribute("capyiot.command-body", command);
                current = new BodyRequest(current, command);
              }
              chain.doFilter(current, res);
            } catch (ServiceFailure error) {
              write(json, req, res, error.status(), error.code());
            } catch (ApiProblem error) {
              write(json, req, res, error.status(), error.code());
            } catch (org.springframework.dao.DataAccessException error) {
              write(json, req, res, 503, "BUSINESS_STORE_UNAVAILABLE");
            }
          }
        };
    var fresh =
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            try {
              String path = HttpPaths.canonical(req.getRequestURI(), req.getContextPath());
              var auth =
                  org.springframework.security.core.context.SecurityContextHolder.getContext()
                      .getAuthentication();
              if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                if (AgentDelegation.isAgent(jwt)) {
                  String key =
                      req.getMethod().equals("GET")
                              && path.startsWith("/api/v1/workorders/commands/")
                          ? path.substring("/api/v1/workorders/commands/".length())
                          : req.getHeader("Idempotency-Key");
                  req.setAttribute(
                      "capyiot.delegation",
                      AgentDelegation.validate(jwt, req.getMethod(), path, key));
                }
                var currentActor = platform.current(jwt);
                if (AgentDelegation.isAgent(jwt)) {
                  currentActor.require("agent.use");
                  currentActor.require("agent.draft.confirm");
                  currentActor.require("workorder.create");
                }
                req.setAttribute("capyiot.actor", currentActor);
              }
              chain.doFilter(req, res);
            } catch (ApiProblem error) {
              write(json, req, res, error.status(), error.code());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
              write(json, req, res, 400, "VALIDATION_ERROR");
            }
          }
        };
    return http.csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                        "/.well-known/service-jwks.json",
                        "/internal/v1/**")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (req, res, error) -> write(json, req, res, 401, "TOKEN_INVALID")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, error) -> write(json, req, res, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler(
                        (req, res, error) -> write(json, req, res, 403, "PERMISSION_DENIED")))
        .addFilterBefore(internal, BearerTokenAuthenticationFilter.class)
        .addFilterBefore(fresh, AnonymousAuthenticationFilter.class)
        .build();
  }

  static void write(
      ObjectMapper json, HttpServletRequest req, HttpServletResponse res, int status, String code)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    json.writeValue(
        res.getOutputStream(),
        Map.of(
            "code",
            code,
            "message",
            status == 401 ? "请重新登录" : "操作暂不可用或无权执行",
            "details",
            Map.of(),
            "traceId",
            req.getRequestId()));
  }

  private static final class BodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    public ServletInputStream getInputStream() {
      var bytes = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        public int read() {
          return bytes.read();
        }

        public boolean isFinished() {
          return bytes.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Async request body is not supported");
        }
      };
    }

    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}

package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.auth.AccessTokenService;
import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "runtime-mock.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class AuthController {

    private final AccessTokenService accessTokenService;
    private final RequestContextFactory requestContextFactory;
    private final RbacService rbacService;

    public AuthController(AccessTokenService accessTokenService,
                          RequestContextFactory requestContextFactory,
                          RbacService rbacService) {
        this.accessTokenService = accessTokenService;
        this.requestContextFactory = requestContextFactory;
        this.rbacService = rbacService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        var context = requestContextFactory.from(request);
        String authorization = request.getHeader("Authorization");
        Map<String, Object> token = authorization != null && authorization.startsWith("Bearer ")
                ? accessTokenService.describe(authorization.substring("Bearer ".length()).trim())
                : Map.of("subject_type", "USER");
        String subjectType = String.valueOf(token.get("subject_type"));
        Map<String, Object> identity = "AGENT".equals(subjectType)
                ? rbacService.describeAgent(context.actor())
                : rbacService.describe(context.actor());
        Map<String, Object> response = new java.util.LinkedHashMap<>(identity);
        response.put("subjectType", subjectType);
        response.put("tokenId", token.get("id"));
        response.put("expiresAt", token.get("expires_at"));
        response.put("identitySource", context.identitySource());
        return response;
    }

    @PatchMapping("/me")
    public Map<String, Object> updateMe(HttpServletRequest httpRequest,
                                        @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        accessTokenService.updateSelfProfile(context, request);
        return withTokenMetadata(rbacService.describe(context.actor()), null);
    }

    @PostMapping("/me/token/replace")
    public Map<String, Object> replaceMyToken(HttpServletRequest httpRequest,
                                              @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        Map<String, Object> token = accessTokenService.replaceSelfToken(context, bearerToken(httpRequest));
        return withTokenMetadata(rbacService.describe(context.actor()), token);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpServletRequest request) {
        var context = requestContextFactory.from(request);
        rbacService.require(context, "USER_MANAGE");
        return accessTokenService.listUsers();
    }

    @PostMapping("/users/{username}/token/replace")
    public Map<String, Object> replaceUserToken(@PathVariable String username,
                                                HttpServletRequest httpRequest,
                                                @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        rbacService.require(context, "USER_MANAGE");
        Map<String, Object> token = accessTokenService.replaceUserToken(context, username, request);
        return withTokenMetadata(rbacService.describe(String.valueOf(token.get("subjectId"))), token);
    }

    @PostMapping("/users/{username}/tokens/renew")
    public Map<String, Object> renewUserTokens(@PathVariable String username,
                                               HttpServletRequest httpRequest,
                                               @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        rbacService.require(context, "USER_MANAGE");
        return accessTokenService.renewUserTokens(context, username, request);
    }

    @DeleteMapping("/users/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String username, HttpServletRequest request) {
        var context = requestContextFactory.from(request);
        rbacService.require(context, "USER_MANAGE");
        accessTokenService.deleteUser(username);
    }

    @GetMapping("/tokens")
    public List<Map<String, Object>> list(HttpServletRequest request) {
        var context = requestContextFactory.from(request);
        rbacService.require(context, "ADMIN");
        return accessTokenService.list();
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(HttpServletRequest httpRequest,
                                     @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        rbacService.require(context, "ADMIN");
        return accessTokenService.issue(context, request);
    }

    @PostMapping("/tokens/{id}/renew")
    public Map<String, Object> renew(@PathVariable String id,
                                     HttpServletRequest httpRequest,
                                     @RequestBody Map<String, Object> request) {
        var context = requestContextFactory.from(httpRequest);
        rbacService.require(context, "ADMIN");
        return accessTokenService.renew(context, id, request);
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest request) {
        var context = requestContextFactory.from(request);
        rbacService.require(context, "ADMIN");
        accessTokenService.revoke(id);
    }

    private Map<String, Object> withTokenMetadata(Map<String, Object> identity,
                                                  Map<String, Object> token) {
        Map<String, Object> response = new LinkedHashMap<>(identity);
        if (token != null) {
            response.put("token", token.get("token"));
            response.put("tokenId", token.get("id"));
            response.put("subjectType", token.get("subjectType"));
            response.put("expiresAt", token.get("expiresAt"));
        }
        return response;
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw PlatformException.unauthorized("Authorization: Bearer token is required");
        }
        return authorization.substring("Bearer ".length()).trim();
    }

}

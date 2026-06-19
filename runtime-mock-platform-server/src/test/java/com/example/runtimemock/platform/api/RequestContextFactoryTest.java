package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.auth.AccessTokenService;
import com.example.runtimemock.platform.auth.AuthProperties;
import com.example.runtimemock.platform.service.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestContextFactoryTest {

    @Test
    void rejectsUnauthenticatedRemoteIdentityHeaders() {
        RequestContextFactory factory = new RequestContextFactory(new AuthProperties(), mock(AccessTokenService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Actor", "system");

        assertThatThrownBy(() -> factory.from(request))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).status())
                .isEqualTo(401);
    }

    @Test
    void acceptsRemoteRequestWithDatabaseBackedBearerToken() {
        AccessTokenService tokenService = mock(AccessTokenService.class);
        when(tokenService.authenticate("agent-secret")).thenReturn(
                new AccessTokenService.TokenPrincipal("token-1", "AGENT", "agent-1", "agent"));
        RequestContextFactory factory = new RequestContextFactory(new AuthProperties(), tokenService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("Authorization", "Bearer agent-secret");

        assertThat(factory.from(request).actor()).isEqualTo("agent-1");
        assertThat(factory.from(request).identitySource()).isEqualTo("agent");
    }
}

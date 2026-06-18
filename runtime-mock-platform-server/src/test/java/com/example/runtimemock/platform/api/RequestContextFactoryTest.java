package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.service.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextFactoryTest {

    @Test
    void rejectsUnauthenticatedRemoteIdentityHeaders() {
        RequestContextFactory factory = new RequestContextFactory("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Actor", "system");

        assertThatThrownBy(() -> factory.from(request))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).status())
                .isEqualTo(401);
    }

    @Test
    void acceptsRemoteRequestWithConfiguredBearerSecret() {
        RequestContextFactory factory = new RequestContextFactory("shared-secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("Authorization", "Bearer shared-secret");
        request.addHeader("X-Actor", "agent-1");
        request.addHeader("X-Identity-Source", "agent");

        assertThat(factory.from(request).actor()).isEqualTo("agent-1");
        assertThat(factory.from(request).identitySource()).isEqualTo("agent");
    }
}

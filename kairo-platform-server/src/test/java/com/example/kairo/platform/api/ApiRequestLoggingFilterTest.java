package com.example.kairo.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestLoggingFilterTest {

    private final ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter();

    @Test
    void generatesAndReturnsCorrelationIdForEveryRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/control/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String correlationId = response.getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(request.getAttribute(ApiRequestLoggingFilter.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo(correlationId);
    }

    @Test
    void preservesSafeClientCorrelationIdAndRejectsLogInjection() throws Exception {
        MockHttpServletRequest safe = new MockHttpServletRequest("POST", "/api/v1/rules");
        safe.addHeader("X-Correlation-Id", "release-17:attempt-2");
        MockHttpServletResponse safeResponse = new MockHttpServletResponse();
        filter.doFilter(safe, safeResponse, new MockFilterChain());
        assertThat(safeResponse.getHeader("X-Correlation-Id")).isEqualTo("release-17:attempt-2");

        MockHttpServletRequest injected = new MockHttpServletRequest("POST", "/api/v1/rules");
        injected.addHeader("X-Correlation-Id", "good\nforged=entry");
        MockHttpServletResponse injectedResponse = new MockHttpServletResponse();
        filter.doFilter(injected, injectedResponse, new MockFilterChain());
        assertThat(injectedResponse.getHeader("X-Correlation-Id"))
                .isNotEqualTo("good\nforged=entry")
                .doesNotContain("\n");
    }
}

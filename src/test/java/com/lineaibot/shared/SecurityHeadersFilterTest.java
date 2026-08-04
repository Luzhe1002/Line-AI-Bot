package com.lineaibot.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineaibot.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    @Test
    void productionResponsesEnableHsts() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setEnvironment("production");
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }
}

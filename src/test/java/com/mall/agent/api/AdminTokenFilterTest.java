package com.mall.agent.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminTokenFilterTest {

    private int statusFor(String configured, String sent) throws Exception {
        var filter = new AdminTokenFilter(configured);
        var req = new MockHttpServletRequest("POST", "/admin/kb/reindex");
        if (sent != null) req.addHeader("X-Admin-Token", sent);
        var res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        if (res.getStatus() == 200) verify(chain).doFilter(req, res); else verify(chain, never()).doFilter(req, res);
        return res.getStatus();
    }

    @Test
    void correctTokenPasses() throws Exception {
        assertThat(statusFor("secret", "secret")).isEqualTo(200);
    }

    @Test
    void blankConfiguredTokenRejectsEverythingEvenEmptyHeader() throws Exception {
        assertThat(statusFor("", "")).isEqualTo(401);
        assertThat(statusFor("", null)).isEqualTo(401);
    }

    @Test
    void wellKnownDefaultTokenIsNeverAccepted() throws Exception {
        assertThat(statusFor("change-me", "change-me")).isEqualTo(401);
    }
}

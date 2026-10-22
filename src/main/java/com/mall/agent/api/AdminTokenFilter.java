package com.mall.agent.api;

import com.mall.agent.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** /admin/** 必须带 X-Admin-Token(值来自环境变量 ADMIN_TOKEN)。 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public AdminTokenFilter(AgentProperties props) {
        this.expected = props.getAdminToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = req.getHeader("X-Admin-Token");
        // 常量时间比较,避免通过响应时间猜 token
        if (token != null && MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid X-Admin-Token\"}");
    }
}

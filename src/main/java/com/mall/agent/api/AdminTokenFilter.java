package com.mall.agent.api;

import org.springframework.beans.factory.annotation.Value;
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
    private final boolean configured;

    public AdminTokenFilter(@Value("${agent.admin-token:}") String adminToken) {
        this.expected = adminToken.getBytes(StandardCharsets.UTF_8);
        // 没配置或还是众所周知的占位值时,管理接口一律拒绝,而不是放行默认口令
        this.configured = !adminToken.isBlank() && !adminToken.equals("change-me");
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
        if (configured && token != null && MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid X-Admin-Token\"}");
    }
}

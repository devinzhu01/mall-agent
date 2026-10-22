package com.mall.agent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /chat 和 /memory/** 必须带 X-User-Id,缺失返回 401。
 * 本项目不做登录体系:真实系统里这个头应由网关在认证后注入,而不是由前端随意填写。
 */
@Component
public class UserIdFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/chat") || path.startsWith("/memory/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String userId = req.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing X-User-Id\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}

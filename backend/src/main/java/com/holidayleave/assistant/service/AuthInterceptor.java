package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks session.logged_in on every request.
 * - API routes (/api/**) return 401 JSON.
 * - Page routes redirect to /login.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AppProperties props;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        boolean loggedIn = session != null && Boolean.TRUE.equals(session.getAttribute("logged_in"));

        if (!loggedIn) {
            String path = request.getRequestURI();
            if (path.startsWith("/api/") || path.startsWith("/reports/")) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorised\"}");
                return false;
            } else {
                response.sendRedirect("/login");
                return false;
            }
        }
        return true;
    }
}

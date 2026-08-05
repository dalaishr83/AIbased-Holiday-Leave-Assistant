package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.SecretService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Handles GET /login, POST /login, GET /logout.
 *
 * Credentials are loaded from SecretService (file-backed JSON).
 * On successful login the session receives:
 *   - logged_in = true
 *   - role      = "admin" | "employee"
 *   - session_id = random UUID (used for pending-vacation keying)
 */
@Controller
public class AuthController {

    @Autowired
    private SecretService secretService;

    @GetMapping("/login")
    public String loginPage(Model model, HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("logged_in"))) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          HttpServletRequest request,
                          HttpSession session,
                          Model model) {

        String matchedRole = resolveRole(username, password);

        if (matchedRole != null) {
            session.setAttribute("logged_in",  true);
            session.setAttribute("role",        matchedRole);
            session.setAttribute("session_id",  UUID.randomUUID().toString().replace("-", ""));
            return "redirect:/";
        }

        model.addAttribute("error", "Invalid username or password.");
        return "login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    /**
     * Tries to match the submitted credentials against each role in secret.json.
     * Returns the matched role name ("admin" or "employee"), or null if none match.
     */
    private String resolveRole(String username, String password) {
        for (String role : new String[]{"admin", "employee"}) {
            String storedUsername = secretService.getUsername(role);
            String storedHash     = secretService.getHash(role);
            if (storedUsername == null || storedHash == null) continue;
            if (!timingSafeEquals(username, storedUsername)) continue;
            try {
                if (BCrypt.checkpw(password, storedHash)) return role;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Timing-safe string comparison to prevent timing attacks. */
    private boolean timingSafeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}

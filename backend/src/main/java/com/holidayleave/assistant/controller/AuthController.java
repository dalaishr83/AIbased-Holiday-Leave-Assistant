package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.config.AppProperties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Handles GET /login, POST /login, GET /logout.
 */
@Controller
public class AuthController {

    @Autowired
    private AppProperties props;

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
        boolean usernameOk = timingSafeEquals(username, props.getLoginUsername());
        boolean passwordOk = false;
        if (!props.getLoginPasswordHash().isEmpty()) {
            try {
                passwordOk = BCrypt.checkpw(password, props.getLoginPasswordHash());
            } catch (Exception ignored) {}
        }

        if (usernameOk && passwordOk) {
            session.setAttribute("logged_in", true);
            session.setAttribute("session_id", UUID.randomUUID().toString().replace("-", ""));
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

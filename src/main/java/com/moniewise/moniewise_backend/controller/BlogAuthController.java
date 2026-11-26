package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/blog/admin")
public class BlogAuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final AuthSessionService authSessionService;
    private final AbuseProtectionService abuseProtectionService;

    public BlogAuthController(UserService userService,
                              JwtUtil jwtUtil,
                              AuthSessionService authSessionService,
                              AbuseProtectionService abuseProtectionService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.authSessionService = authSessionService;
        this.abuseProtectionService = abuseProtectionService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> blogAdminLogin(@RequestBody Map<String, String> request,
                                            HttpServletRequest httpRequest) {
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required"));
        }

        String throttleKey = abuseProtectionService.buildKey(email.trim(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.BLOG_LOGIN, throttleKey);

        try {
            User user = userService.login(email.trim(), password);

            if (user.getRole() != Role.ADMIN) {
                abuseProtectionService.recordFailure(AbuseProtectionService.BLOG_LOGIN, throttleKey);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid credentials"));
            }

            abuseProtectionService.recordSuccess(AbuseProtectionService.BLOG_LOGIN, throttleKey);
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String sessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateBlogAdminToken(userDetails, sessionId);

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "scope", "blog",
                    "message", "Blog admin access granted. This token works only on /admin/blog endpoints."
            ));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.BLOG_LOGIN, throttleKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }
    }
}

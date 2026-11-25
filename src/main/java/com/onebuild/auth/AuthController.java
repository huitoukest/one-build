package com.onebuild.auth;

import com.onebuild.common.ApiException;
import com.onebuild.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final LoginFailLimiter limiter;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          LoginFailLimiter limiter) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.limiter = limiter;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        if (body == null || body.username() == null || body.username().isBlank()
                || body.password() == null) {
            throw ApiException.badRequest("用户名和密码不能为空");
        }
        String key = request.getRemoteAddr() + ":" + body.username();
        if (limiter.isLocked(key)) {
            throw new ApiException(429, "LOGIN_LOCKED", "登录失败次数过多，请稍后再试");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.username(), body.password()));
            request.getSession(true);
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            limiter.recordSuccess(key);
            return ApiResponse.ok(me(authentication));
        } catch (Exception e) {
            limiter.recordFailure(key);
            throw new ApiException(401, "BAD_CREDENTIALS", "用户名或密码错误");
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw ApiException.unauthorized("未登录");
        }
        return ApiResponse.ok(me(authentication));
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    private Map<String, Object> me(Authentication authentication) {
        return Map.of(
                "username", authentication.getName(),
                "roles", authentication.getAuthorities().stream()
                        .map(a -> a.getAuthority()).toList());
    }
}

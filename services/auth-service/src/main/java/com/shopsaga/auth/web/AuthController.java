package com.shopsaga.auth.web;

import com.shopsaga.auth.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * POST /auth/login — 사용자/비밀번호 검증 후 RS256 JWT 발급.
 *
 * <p>인증 실패 시 401을 <b>명시적으로</b> 반환한다. (컨트롤러에서 던진 AuthenticationException 은
 * 시큐리티 필터가 자동 401 변환해 주지 않으므로 — 던지면 500이 된다.)
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(req.username());
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // ROLE_ 접두사를 떼고 순수 역할명만 claim 에 담는다(리소스 서버가 다시 ROLE_ 부여).
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        TokenService.TokenResult result = tokenService.issue(req.username(), roles);
        return ResponseEntity.ok(Map.of(
                "token", result.token(),
                "expiresAt", result.expiresAt().toString()));
    }
}

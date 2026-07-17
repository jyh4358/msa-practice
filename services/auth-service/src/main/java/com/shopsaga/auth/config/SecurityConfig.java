package com.shopsaga.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * auth-service 보안: 로그인·JWKS·health 는 공개, 그 외는 인증 필요(현재는 그 외 엔드포인트 없음).
 * 인메모리 사용자 2명(alice=USER, admin=USER+ADMIN)으로 데모.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/oauth2/jwks", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        // roles("USER") 는 내부적으로 권한 "ROLE_USER" 로 저장된다. 로그인 시 ROLE_ 를 떼고 클레임에 담는다.
        return new InMemoryUserDetailsManager(
                User.builder().username("alice").password(encoder.encode("secret"))
                        .roles("USER").build(),
                User.builder().username("admin").password(encoder.encode("admin123"))
                        .roles("USER", "ADMIN").build());
    }
}

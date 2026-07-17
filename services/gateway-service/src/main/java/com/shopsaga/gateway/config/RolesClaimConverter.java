package com.shopsaga.gateway.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** roles 클레임(["USER","ADMIN"]) → ROLE_ 접두사를 붙인 GrantedAuthority(리액티브). */
final class RolesClaimConverter {

    private RolesClaimConverter() {
    }

    static Converter<Jwt, Mono<AbstractAuthenticationToken>> rolesConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<?> roles = (List<?>) jwt.getClaims().getOrDefault("roles", Collections.emptyList());
            return roles.stream()
                    .map(Object::toString)
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toList());
        });
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}

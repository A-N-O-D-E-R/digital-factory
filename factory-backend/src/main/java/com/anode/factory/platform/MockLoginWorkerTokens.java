package com.anode.factory.platform;

import com.anode.security.oidc.ClaimsToRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * With the mock login (a try-out without an identity provider) the security starter sets up no bearer
 * token authentication, so workers could not sign in: their self-issued tokens are accepted here, as in
 * the other modes (ActiveWorkerTokenValidator still checks them through the decoder).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "anode.security", name = "mock", havingValue = "true")
class MockLoginWorkerTokens {

    @Bean
    Customizer<HttpSecurity> workerTokens(JwtDecoder decoder, ClaimsToRoleMapper roles) {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            roles.map(jwt.getClaims()).forEach(role -> authorities.add(new SimpleGrantedAuthority(role)));
            return authorities;
        });
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        ProviderManager manager = new ProviderManager(provider);
        AuthenticationManagerResolver<HttpServletRequest> resolver = request -> manager;
        return http -> {
            try {
                http.oauth2ResourceServer(server -> server.authenticationManagerResolver(resolver));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }
}

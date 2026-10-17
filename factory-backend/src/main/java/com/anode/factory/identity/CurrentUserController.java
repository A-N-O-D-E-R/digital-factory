package com.anode.factory.identity;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in and what they may do. */
@RestController
@RequestMapping("/api/user")
class CurrentUserController {

    record CurrentUser(String name, Set<String> roles, Set<String> permissions) {}

    @GetMapping
    CurrentUser currentUser(Authentication authentication) {
        Set<String> authorities = new TreeSet<>();
        authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).forEach(authorities::add);
        Set<String> roles = new TreeSet<>();
        Set<String> permissions = new TreeSet<>();
        Arrays.stream(Role.values()).filter(role -> authorities.contains(role.authority())).forEach(role -> {
            roles.add(role.name());
            role.permissions().forEach(permission -> permissions.add(permission.value()));
        });
        return new CurrentUser(authentication.getName(), roles, permissions);
    }
}

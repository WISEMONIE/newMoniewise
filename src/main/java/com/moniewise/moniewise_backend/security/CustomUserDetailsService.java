package com.moniewise.moniewise_backend.security;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        // Build the Spring Security authority from the User's actual Role enum.
        // Spring's hasRole('ADMIN') internally checks for the "ROLE_ADMIN" authority,
        // so we must prefix with "ROLE_". Never hardcode a fixed role here — that
        // would silently prevent any admin from accessing protected endpoints.
        String authority = "ROLE_" + user.getRole().name();   // e.g. "ROLE_USER", "ROLE_ADMIN"
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(authority)
                .build();
    }
}
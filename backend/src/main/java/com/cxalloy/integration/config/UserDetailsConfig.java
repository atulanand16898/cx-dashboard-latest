package com.cxalloy.integration.config;

import com.cxalloy.integration.model.Person;
import com.cxalloy.integration.repository.PersonRepository;
import com.cxalloy.integration.service.ProjectAccessService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * Separated from SecurityConfig to break the circular dependency:
 *   SecurityConfig → JwtAuthenticationFilter → UserDetailsService → SecurityConfig (cycle!)
 *
 * By moving UserDetailsService and PasswordEncoder here, SecurityConfig can inject
 * JwtAuthenticationFilter safely without any cycle.
 */
@Configuration
public class UserDetailsConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder,
                                                 PersonRepository personRepository,
                                                 ProjectAccessService projectAccessService) {
        return username -> {
            String normalized = username == null ? "" : username.trim().toLowerCase();

            if ("admin".equals(normalized)) {
                return User.builder()
                        .username("admin")
                        .password(passwordEncoder.encode("admin123"))
                        .roles("ADMIN", "USER")
                        .build();
            }

            if (!StringUtils.hasText(normalized) || !normalized.contains("@")) {
                throw new UsernameNotFoundException("User not found");
            }

            if (!projectAccessService.isAssignedEmail(normalized)) {
                throw new UsernameNotFoundException("User is not assigned to any projects");
            }

            Person person = personRepository.findAllByEmailIgnoreCase(normalized).stream().findFirst().orElse(null);
            String displayName = person == null
                    ? normalized
                    : ((person.getFirstName() == null ? "" : person.getFirstName()) + " " +
                    (person.getLastName() == null ? "" : person.getLastName())).trim();

            return User.builder()
                    .username(normalized)
                    .password(passwordEncoder.encode(normalized))
                    .roles("USER")
                    .build();
        };
    }
}

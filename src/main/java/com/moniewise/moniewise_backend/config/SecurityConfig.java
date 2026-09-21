package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.JwtAuthenticationFilter;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.HowToUseWisemonieNudgeService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)   // activates @PreAuthorize / @PostAuthorize on all beans
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final HowToUseWisemonieNudgeService howToUseWisemonieNudgeService;

    @Value("${app.security.dev-mode:true}")
    private boolean devMode;

    @Value("${app.security.allowed-origins:}")
    private String allowedOrigins;

    public SecurityConfig(
            @Lazy JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtUtil jwtUtil,
            UserDetailsService userDetailsService,
            UserService userService,
            AuthSessionService authSessionService,
            HowToUseWisemonieNudgeService howToUseWisemonieNudgeService
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.howToUseWisemonieNudgeService = howToUseWisemonieNudgeService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors().and()
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .exceptionHandling().authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .and()
                .authorizeRequests()
                .antMatchers("/auth/signup", "/auth/verify-signup-otp", "/auth/resend-signup-otp", "/auth/bvn/pre-verify", "/auth/login", "/tnc/**", "/users/otp/generate", "/users/otp/verify", "/auth/forgot-password", "/auth/verify-reset-otp", "/auth/reset-password").permitAll()
                .antMatchers("/api/webhooks/monnify", "/api/webhooks/securewave",
                             "/api/webhooks/providus", "/api/webhooks/rubies").permitAll()   // Rubies webhook must be open — no JWT
                // Public brand assets (email logo etc.). Email clients fetch the logo
                // with no JWT — without this, every email renders a broken image (401).
                .antMatchers("/images/**").permitAll()
                .antMatchers("/.well-known/**", "/apple-app-site-association", "/open", "/open/**").permitAll()
                // Public legal pages — Google Play (and reviewers generally) require the
                // privacy policy and the account-deletion page to be world-readable HTM
                // with no login. The JSON /legal/** API for the in-app viewer stays
                // authenticated below.
                .antMatchers("/privacy-policy", "/terms-of-use", "/delete-account").permitAll()
                .antMatchers("/blog", "/blog/**", "/blog/api/**", "/blog/admin/login").permitAll()
                .antMatchers("/app/version-check", "/app/update-status", "/app/config", "/app/whats-new").permitAll()
                .antMatchers("/ws", "/ws/**", "/ws-sockjs", "/ws-sockjs/**").permitAll()
                .antMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .antMatchers("/actuator/metrics/**", "/actuator/prometheus").hasRole("ADMIN")
                .antMatchers("/admin/**").hasRole("ADMIN")   // URL-level guard (defence-in-depth alongside @PreAuthorize)
                .antMatchers("/auth/logout", "/auth/refresh", "/auth/delete").authenticated()
                .antMatchers("/users/**", "/notifications/**", "/disbursements/**", "/transactions/**", "/legal/**", "/ai/**", "/budgets/**", "/envelopes/**", "/wallets/**", "/transactions/pin/**", "/beneficiaries/**", "/savings/**", "/analytics/**", "/badges/**").authenticated()
                .antMatchers(HttpMethod.PATCH, "/users/tnc").authenticated()
                .anyRequest().authenticated()
                .and()
                .oauth2Login()
                .userInfoEndpoint().oidcUserService(oidcUserService())
                .and()
                .successHandler((request, response, authentication) -> {
                    DefaultOidcUser oidcUser = (DefaultOidcUser) authentication.getPrincipal();
                    String email = oidcUser.getEmail();
                    String name = oidcUser.getFullName();
                    User user = userService.findOrCreateOAuthUser(email, name);
                    UserDetails userDetails = userService.loadUserByUsername(email);
                    String sessionId = authSessionService.createSession(user);
                    String token = jwtUtil.generateToken(userDetails, sessionId);
                    howToUseWisemonieNudgeService.sendImmediateGuideAfterAuth(user);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"token\":\"" + token + "\"}");
                })
                .and()
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new com.moniewise.moniewise_backend.security.BlogScopeFilter(), JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        if (devMode) {
            configuration.addAllowedOriginPattern("*");
        } else {
            List<String> origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin -> !origin.isEmpty()).collect(Collectors.toList());
            if (origins.isEmpty()) {
                throw new IllegalStateException("Production CORS requires explicit app.security.allowed-origins");
            }
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "X-Signature", "monnify-signature", "X-App-Platform", "X-App-Version", "X-App-Build"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public OidcUserService oidcUserService() {
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setAccessibleScopes(Set.of("email", "profile"));
        return oidcUserService;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}

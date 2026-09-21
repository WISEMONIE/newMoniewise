package com.moniewise.moniewise_backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String SECRET_KEY;

    @Value("${jwt.expiration}")
    private Long expiration;

    private Key secret;

    // Initialize after properties are set
    @PostConstruct
    public void init() {
        if (SECRET_KEY == null || SECRET_KEY.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT secret is not configured");
        }
        if (expiration == null || expiration <= 0) {
            throw new IllegalArgumentException("Invalid expiration time");
        }
        this.secret = new SecretKeySpec(
                Base64.getDecoder().decode(SECRET_KEY),
                SignatureAlgorithm.HS512.getJcaName()
        );
    }


    // Extract a specific claim from the token
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getClaims(token);
        return claimsResolver.apply(claims);
    }

    // Get all claims from the token
    public Claims getClaims(String token) {
        return Jwts.parser()
                .setSigningKey(secret)
                .parseClaimsJws(token)
                .getBody();
    }

    // Extract the email (subject) from the token
    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    // Extract the expiration date from the token
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    // Check if the token is expired
    public Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // Generate token with proper expiration
    public String generateToken(UserDetails userDetails, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", userDetails.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .collect(Collectors.joining(",")));
        claims.put("sessionId", sessionId);
        return createToken(claims, userDetails.getUsername());
    }

    public String generateBlogAdminToken(UserDetails userDetails, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "ROLE_ADMIN");
        claims.put("sessionId", sessionId);
        claims.put("scope", "blog");
        return createToken(claims, userDetails.getUsername());
    }

    public String extractScope(String token) {
        try {
            return extractClaim(token, claims -> claims.get("scope", String.class));
        } catch (Exception e) {
            return null;
        }
    }

    // 👇 ADD THIS METHOD TO JwtUtil.java
    public String extractSessionId(String token) {
        return extractClaim(token, claims -> claims.get("sessionId", String.class));
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration)) // Use injected expiration
                .signWith(SignatureAlgorithm.HS512, secret)
                .compact();
    }

    // Validate the token against UserDetails
    public boolean validateToken(String token, UserDetails userDetails) {
        try {
            final String email = extractEmail(token);
            return (email.equals(userDetails.getUsername()) && !isTokenExpired(token));
        } catch (Exception e) {
            return false;
        }
    }
}
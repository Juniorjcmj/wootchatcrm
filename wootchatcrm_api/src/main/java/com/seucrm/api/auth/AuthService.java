package com.seucrm.api.auth;

import com.seucrm.config.JwtService;
import com.seucrm.domain.user.User;
import com.seucrm.domain.user.UserRepository;
import com.seucrm.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest req) {
        authManager.authenticate(
            new UsernamePasswordAuthenticationToken(req.email(), req.password()));

        User user = userRepository.findByEmail(req.email())
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.UNAUTHORIZED));

        if (!user.getEnabled()) {
            throw new BusinessException("User account is disabled", HttpStatus.FORBIDDEN);
        }

        // NOTE: o update de lastLoginAt está temporariamente desativado.
        // Hibernate manda role como VARCHAR e a coluna é o enum nativo user_role do Postgres.
        // Resolver depois com @JdbcType(PostgreSQLEnumJdbcType.class) no campo role do User.
        // user.setLastLoginAt(Instant.now());
        // userRepository.save(user);

        String accessToken = jwtService.generateAccessToken(user);

        return new LoginResponse(
            accessToken,
            user.getId().toString(),
            user.getTenantId().toString(),
            user.getName(),
            user.getEmail(),
            user.getRole().name(),
            user.getAvatarUrl()
        );
    }
}

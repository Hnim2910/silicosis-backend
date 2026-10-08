package com.sillicosis.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.auth.dto.AuthResponse;
import com.sillicosis.backend.common.TokenHasher;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;

/** Cấp access token (JWT HS256) và refresh token (ngẫu nhiên, lưu hash). */
@Service
@RequiredArgsConstructor
public class TokenService {

	private final JwtEncoder jwtEncoder;
	private final RefreshTokenRepository refreshTokenRepository;
	private final AppProperties props;
	private final Clock clock;

	@Transactional
	public AuthResponse issueTokens(User user) {
		Instant now = clock.instant();
		AppProperties.Jwt jwt = props.jwt();

		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(jwt.issuer())
			.subject(user.getId().toString())
			.issuedAt(now)
			.expiresAt(now.plus(jwt.accessTokenTtl()))
			.id(UUID.randomUUID().toString())
			.claim("role", user.getRole().name())
			.build();
		String accessToken = jwtEncoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();

		String refreshToken = TokenHasher.newToken();
		RefreshToken entity = new RefreshToken();
		entity.setUser(user);
		entity.setTokenHash(TokenHasher.sha256(refreshToken));
		entity.setExpiresAt(now.plus(jwt.refreshTokenTtl()));
		refreshTokenRepository.save(entity);

		return new AuthResponse(accessToken, refreshToken, "Bearer", jwt.accessTokenTtl().toSeconds(),
				jwt.refreshTokenTtl().toSeconds(), UserResponse.from(user));
	}

	@Transactional
	public void revokeAll(UUID userId) {
		refreshTokenRepository.revokeAllForUser(userId, clock.instant());
	}

}

package com.autospec.controller;

import com.autospec.dto.LoginRequest;
import com.autospec.dto.LoginResponse;
import com.autospec.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final boolean secureCookie;
    private final String sameSite;
    private final boolean exposeSessionToken;

    public AuthController(
            AuthService authService,
            @Value("${autospec.auth.session.cookie-secure:false}") boolean secureCookie,
            @Value("${autospec.auth.session.same-site:Strict}") String sameSite,
            @Value("${autospec.auth.session.expose-token-in-response:false}") boolean exposeSessionToken
    ) {
        this.authService = authService;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.exposeSessionToken = exposeSessionToken;
    }

    @PostMapping("/login")
    public LoginResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        var user = authService.login(request.username(), request.password(), httpRequest.getRemoteAddr());
        String token = authService.issueSession(user);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token).toString());
        return LoginResponse.from(user, exposeSessionToken ? token : null);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            HttpServletResponse response
    ) {
        authService.revokeSession(sessionToken);
        response.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString());
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from("AUTOSPEC_SESSION", token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path("/")
                .maxAge(authService.sessionTtl())
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from("AUTOSPEC_SESSION", "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
    }
}

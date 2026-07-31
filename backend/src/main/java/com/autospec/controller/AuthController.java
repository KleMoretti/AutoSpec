package com.autospec.controller;

import com.autospec.dto.LoginRequest;
import com.autospec.dto.LoginResponse;
import com.autospec.service.AuthService;
import org.springframework.http.HttpStatus;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        authService.ensureDemoOwner();
        var user = authService.login(request.username(), request.password());
        return LoginResponse.from(user, authService.issueSession(user));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        authService.revokeSession(sessionToken);
    }
}

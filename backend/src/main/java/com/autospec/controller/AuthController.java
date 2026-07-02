package com.autospec.controller;

import com.autospec.dto.LoginRequest;
import com.autospec.dto.LoginResponse;
import com.autospec.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}

package com.autospec.dto;

import com.autospec.entity.UserAccount;

public record LoginResponse(Long userId, String username, String displayName, String sessionToken) {

    public static LoginResponse from(UserAccount user, String sessionToken) {
        return new LoginResponse(user.getId(), user.getUsername(), user.getDisplayName(), sessionToken);
    }
}

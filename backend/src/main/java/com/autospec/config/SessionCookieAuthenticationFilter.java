package com.autospec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

@Component
public class SessionCookieAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_HEADER = "X-AutoSpec-Session-Token";
    public static final String SESSION_COOKIE = "AUTOSPEC_SESSION";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getHeader(SESSION_HEADER) != null) {
            filterChain.doFilter(request, response);
            return;
        }
        String cookieToken = findSessionCookie(request.getCookies());
        if (cookieToken == null) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return SESSION_HEADER.equalsIgnoreCase(name) ? cookieToken : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return SESSION_HEADER.equalsIgnoreCase(name)
                        ? Collections.enumeration(Collections.singleton(cookieToken))
                        : super.getHeaders(name);
            }
        };
        filterChain.doFilter(wrapped, response);
    }

    private String findSessionCookie(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

package com.autospec.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private static final MediaType YAML = MediaType.valueOf("application/yaml;charset=utf-8");
    private static final String OPENAPI_RESOURCE = "contracts/autospec-backend-v1.openapi.yaml";

    @GetMapping(value = "/openapi", produces = "application/yaml")
    public ResponseEntity<String> openApi() {
        try {
            return ResponseEntity.ok()
                    .contentType(YAML)
                    .body(new ClassPathResource(OPENAPI_RESOURCE).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAPI contract is not available", ex);
        }
    }
}

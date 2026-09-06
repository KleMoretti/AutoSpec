package com.autospec.controller;

import com.autospec.dto.ToolGatewayRequest;
import com.autospec.dto.ToolGatewayResponse;
import com.autospec.service.ToolGatewayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only endpoint; it is not a user-facing project API. */
@RestController
@RequestMapping("/internal/tool-gateway")
public class ToolGatewayController {
    private final ToolGatewayService service;

    public ToolGatewayController(ToolGatewayService service) {
        this.service = service;
    }

    @PostMapping("/requests")
    public ToolGatewayResponse execute(
            @RequestHeader(value = ToolGatewayService.SERVICE_HEADER, required = false) String serviceToken,
            @RequestBody ToolGatewayRequest request
    ) {
        service.requireServiceToken(serviceToken);
        return service.execute(request);
    }
}

package com.autospec;

import com.autospec.service.AgentEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApiContractEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentEngineClient agentEngineClient;

    @Test
    void openApiContractIsServedWithoutSessionForToolingAndReview() throws Exception {
        mockMvc.perform(get("/api/contracts/openapi"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/yaml")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.0.3")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ApiErrorResponse")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PaginationLimit")));
    }
}

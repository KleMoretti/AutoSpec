package com.autospec;

import com.autospec.controller.WorkflowVersionController;
import com.autospec.dto.WorkflowVersionResponse;
import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowVersionControllerTest {

    @Test
    void listsAuthenticatedWorkflowVersionsForReplaySelection() {
        WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowVersionMapper versionMapper = mock(WorkflowVersionMapper.class);
        ProjectAccessService accessService = mock(ProjectAccessService.class);
        WorkflowVersionController controller = new WorkflowVersionController(
                definitionMapper,
                versionMapper,
                accessService
        );
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(4L);
        definition.setWorkflowKey("autospec-v5");
        WorkflowVersion version = new WorkflowVersion();
        version.setId(9L);
        version.setDefinitionId(4L);
        version.setVersion("v5.1");
        version.setStatus("PUBLISHED");
        version.setContentHash("hash-1");
        when(definitionMapper.selectOne(any(Wrapper.class))).thenReturn(definition);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(version));

        List<WorkflowVersionResponse> response = controller.versions("autospec-v5", "session-1");

        assertThat(response).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(9L);
            assertThat(value.workflowKey()).isEqualTo("autospec-v5");
            assertThat(value.status()).isEqualTo("PUBLISHED");
        });
        verify(accessService).resolveUserId("session-1");
    }
}

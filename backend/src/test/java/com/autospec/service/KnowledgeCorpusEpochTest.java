package com.autospec.service;

import com.autospec.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeCorpusEpochTest {
    @Autowired
    private KnowledgeCorpusEpochService corpusEpochService;

    @Autowired
    private ProjectService projectService;

    @Test
    void epochIsMonotonicAndRecordsInvalidationBoundaries() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("epoch-" + UUID.randomUUID());
        project.setOriginalRequirement("cache invalidation test");
        project.setStatus("CREATED");
        projectService.save(project);

        assertThat(corpusEpochService.current(project.getId())).isEqualTo(1L);
        assertThat(corpusEpochService.bump(project.getId(), "ARTIFACT_APPROVED")).isEqualTo(2L);
        assertThat(corpusEpochService.bump(project.getId(), "PROJECT_MEMBER_UPDATED")).isEqualTo(3L);
        assertThat(corpusEpochService.current(project.getId())).isEqualTo(3L);
    }
}

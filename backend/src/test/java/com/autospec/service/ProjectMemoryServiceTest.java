package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.ProjectMemoryFact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ProjectMemoryFactMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectMemoryServiceTest {

    @Test
    void projectsTypedVersionedFactsFromPrdArtifact() {
        ProjectMemoryFactMapper mapper = mock(ProjectMemoryFactMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(ProjectMemoryFact.class))).thenReturn(1);
        ProjectMemoryService service = new ProjectMemoryService(mapper, new ObjectMapper());

        Artifact artifact = new Artifact();
        artifact.setId(41L);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setVersion(3);
        artifact.setContentHash("a".repeat(64));
        artifact.setContent("""
                {
                  "project_name":"Approvals",
                  "core_features":[
                    {"requirement_id":"REQ-SUBMIT","name":"Submit","description":"Submit expense","priority":"MUST"},
                    {"requirement_id":"REQ-APPROVE","name":"Approve","description":"Approve expense","priority":"MUST"}
                  ],
                  "business_boundaries":["Managers approve only their department"],
                  "non_functional_requirements":["Every approval is audited"]
                }
                """);
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(19L);

        service.projectArtifact(artifact, nodeRun, 11L);

        ArgumentCaptor<ProjectMemoryFact> captor = ArgumentCaptor.forClass(ProjectMemoryFact.class);
        verify(mapper, times(5)).insert(captor.capture());
        List<ProjectMemoryFact> facts = captor.getAllValues();
        assertThat(facts).extracting(ProjectMemoryFact::getFactType)
                .containsExactlyInAnyOrder(
                        "ARTIFACT", "REQUIREMENT", "REQUIREMENT", "CONSTRAINT", "CONSTRAINT"
                );
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.getProjectId()).isEqualTo(7L);
            assertThat(fact.getVersion()).isEqualTo(1);
            assertThat(fact.getConflictStatus()).isEqualTo("ACTIVE");
            assertThat(fact.getSourceWorkflowRunId()).isEqualTo(11L);
            assertThat(fact.getSourceNodeRunId()).isEqualTo(19L);
            assertThat(fact.getSourceArtifactId()).isEqualTo(41L);
            assertThat(fact.getContentHash()).matches("[0-9a-f]{64}");
        });
        assertThat(facts).filteredOn(fact -> "REQUIREMENT".equals(fact.getFactType()))
                .extracting(ProjectMemoryFact::getFactKey)
                .containsExactlyInAnyOrder("REQ-SUBMIT", "REQ-APPROVE");
    }

    @Test
    void recallsOnlyNodeRelevantFactTypes() {
        ProjectMemoryFactMapper mapper = mock(ProjectMemoryFactMapper.class);
        ProjectMemoryFact fact = new ProjectMemoryFact();
        fact.setId(1L);
        fact.setProjectId(7L);
        fact.setFactType("REQUIREMENT");
        fact.setFactKey("REQ-1");
        fact.setValueJson("{\"statement\":\"must approve\"}");
        fact.setContentHash("b".repeat(64));
        fact.setVersion(1);
        fact.setConflictStatus("ACTIVE");
        fact.setSourceType("WORKFLOW_ARTIFACT");
        fact.setSourceRef("artifact:1:v1");
        fact.setValidFromAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(fact));

        ProjectMemoryService service = new ProjectMemoryService(mapper, new ObjectMapper());

        assertThat(service.recallForNode(7L, "architect"))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.factType()).isEqualTo("REQUIREMENT");
                    assertThat(value.value().path("statement").asText()).isEqualTo("must approve");
                });
        assertThat(ProjectMemoryService.FACT_TYPES).isEqualTo(Set.of(
                "REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"
        ));
    }

    @Test
    void unapprovedArtifactDoesNotSupersedeApprovedMemory() {
        ProjectMemoryFactMapper mapper = mock(ProjectMemoryFactMapper.class);
        ProjectMemoryFact approved = new ProjectMemoryFact();
        approved.setId(9L);
        approved.setProjectId(7L);
        approved.setFactType("REQUIREMENT");
        approved.setFactKey("REQ-1");
        approved.setContentHash("a".repeat(64));
        approved.setVersion(1);
        approved.setTrustStatus("APPROVED");
        approved.setConflictStatus("ACTIVE");
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectOne(any())).thenReturn(approved);
        when(mapper.insert(any(ProjectMemoryFact.class))).thenReturn(1);
        ProjectMemoryService service = new ProjectMemoryService(mapper, new ObjectMapper());

        Artifact artifact = new Artifact();
        artifact.setId(42L);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setVersion(2);
        artifact.setStatus("PENDING_REVIEW");
        artifact.setContentHash("b".repeat(64));
        artifact.setContent("""
                {"core_features":[{"requirement_id":"REQ-1","statement":"changed"}]}
                """);

        service.projectArtifact(artifact, null, 12L);

        ArgumentCaptor<ProjectMemoryFact> captor = ArgumentCaptor.forClass(ProjectMemoryFact.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(fact -> {
                    assertThat(fact.getConflictStatus()).isEqualTo("CONFLICT");
                    assertThat(fact.getTrustStatus()).isEqualTo("UNTRUSTED");
                });
    }
}

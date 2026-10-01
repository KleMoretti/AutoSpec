package com.autospec;

import com.autospec.controller.ProjectController;
import com.autospec.dto.ReviewResponse;
import com.autospec.service.ArtifactService;
import com.autospec.service.ArtifactVersionService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.ProjectDashboardService;
import com.autospec.service.ProjectService;
import com.autospec.service.ReviewIssueService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectControllerReviewTest {

    @Test
    void missingReviewReportIsAnEmptySuccessfulReviewResponse() {
        ProjectAccessService accessService = mock(ProjectAccessService.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        when(accessService.resolveUserId("session")).thenReturn(1L);
        when(reviewIssueService.listByProjectId(7L, 50, 0)).thenReturn(List.of());
        when(reviewIssueService.latestReviewScore(7L)).thenReturn(null);

        ProjectController controller = new ProjectController(
                mock(ProjectService.class),
                mock(ArtifactService.class),
                mock(ArtifactVersionService.class),
                reviewIssueService,
                accessService,
                mock(ProjectDashboardService.class)
        );

        ReviewResponse response = controller.review(7L, "session", 50, 0);

        assertThat(response.score()).isNull();
        assertThat(response.issues()).isEmpty();
    }
}

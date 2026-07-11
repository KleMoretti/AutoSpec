package com.autospec.service;

import com.autospec.entity.Project;
import com.autospec.entity.ProjectMember;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class ProjectAccessService {

    private final AuthService authService;
    private final ProjectMemberService projectMemberService;
    private final ProjectService projectService;

    public ProjectAccessService(
            AuthService authService,
            ProjectMemberService projectMemberService,
            ProjectService projectService
    ) {
        this.authService = authService;
        this.projectMemberService = projectMemberService;
        this.projectService = projectService;
    }

    public Long resolveUserId(String sessionToken) {
        return authService.requireSessionUserId(sessionToken);
    }

    public void addOwner(Long projectId, Long userId) {
        boolean exists = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .exists();
        if (exists) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole("OWNER");
        projectMemberService.save(member);
    }

    public void requireProjectRole(Long projectId, Long userId, String... acceptedRoles) {
        Set<String> roles = Set.of(acceptedRoles);
        boolean memberAllowed = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .list()
                .stream()
                .anyMatch(member -> roles.contains(member.getRole()));
        if (memberAllowed) {
            return;
        }
        Project project = projectService.getById(projectId);
        if (project != null && userId.equals(project.getUserId()) && roles.contains("OWNER")) {
            addOwner(projectId, userId);
            return;
        }
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project access denied");
    }

    public Project requireVisibleProject(Long projectId, Long userId) {
        requireProjectRole(projectId, userId, "OWNER", "EDITOR", "VIEWER");
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return project;
    }

    public List<Project> listVisibleProjects(Long userId, int limit, int offset) {
        return projectService.listVisibleProjects(userId, limit, offset);
    }
}

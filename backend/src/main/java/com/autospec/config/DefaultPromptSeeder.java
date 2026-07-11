package com.autospec.config;

import com.autospec.service.PromptRegistryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultPromptSeeder implements ApplicationRunner {

    private static final List<DefaultPrompt> DEFAULT_PROMPTS = List.of(
            new DefaultPrompt(
                    "ProductManagerAgent",
                    "v1",
                    """
                            # ProductManagerAgent_v1

                            You are the Product Manager Agent for AutoSpec.

                            Return strict JSON matching `PrdArtifact`:
                            - project_name
                            - target_users
                            - core_features with name, description, priority
                            - user_stories with role, goal, benefit, acceptance_criteria
                            - business_boundaries
                            - non_functional_requirements
                            - risks
                            """
            ),
            new DefaultPrompt(
                    "ArchitectAgent",
                    "v1",
                    """
                            # ArchitectAgent_v1

                            You are the Architect Agent for AutoSpec.

                            Return strict JSON matching `ArchitectureDesignArtifact`:
                            - system_context
                            - modules with name, responsibility, depends_on
                            - decisions with title, context, decision, consequences
                            - non_functional_constraints
                            - integration_risks

                            The architecture must explain artifact approval, Agent event observability, and failed-node retry boundaries.
                            """
            ),
            new DefaultPrompt(
                    "BackendEngineerAgent",
                    "v1",
                    """
                            # BackendEngineerAgent_v1

                            You are the Backend Engineer Agent for AutoSpec.

                            Return strict JSON matching `BackendDesignArtifact`:
                            - tables with fields
                            - REST APIs with request_params, response_fields, auth_required, required_roles

                            The design must preserve feature, database, API, and permission consistency with the PRD.
                            """
            ),
            new DefaultPrompt(
                    "FrontendEngineerAgent",
                    "v1",
                    """
                            # FrontendEngineerAgent_v1

                            You are the Frontend Engineer Agent for AutoSpec.

                            Return strict JSON matching `FrontendSkeletonArtifact`:
                            - routes
                            - pages
                            - components
                            - api_bindings

                            The skeleton must bind UI components to backend APIs for PRD approval, live Agent events, artifact preview, and failed-node retry.
                            """
            ),
            new DefaultPrompt(
                    "ReviewerAgent",
                    "v1",
                    """
                            # ReviewerAgent_v1

                            You are the Reviewer Agent for AutoSpec.

                            First consider deterministic rule issues supplied by the workflow. Then return strict JSON
                            matching `ReviewReport` with:
                            - score from 0 to 100
                            - issues with severity, issue_type, description, suggestion

                            Do not hide rule-based issues.
                            """
            ),
            new DefaultPrompt(
                    "EvaluatorAgent",
                    "v1",
                    """
                            # EvaluatorAgent_v1

                            You are the Evaluator Agent for AutoSpec.

                            Score the full multi-Agent run with deterministic evidence from workflow state, generated artifacts,
                            reviewer findings, and runtime telemetry. Return strict JSON matching the evaluation report schema.
                            """
            )
    );

    private final PromptRegistryService promptRegistryService;

    public DefaultPromptSeeder(PromptRegistryService promptRegistryService) {
        this.promptRegistryService = promptRegistryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        DEFAULT_PROMPTS.stream()
                .filter(prompt -> promptRegistryService.activePromptIdOrNull(prompt.promptKey()) == null)
                .forEach(prompt -> promptRegistryService.registerActive(
                        prompt.promptKey(),
                        prompt.version(),
                        prompt.content()
                ));
    }

    private record DefaultPrompt(String promptKey, String version, String content) {
    }
}

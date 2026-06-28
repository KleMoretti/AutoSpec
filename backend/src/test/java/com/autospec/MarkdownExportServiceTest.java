package com.autospec;

import com.autospec.service.MarkdownExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MarkdownExportServiceTest {

    @Autowired
    private MarkdownExportService markdownExportService;

    @Test
    void rendersPrdBackendDesignAndReviewSections() {
        String prdJson = """
                {
                  "project_name": "Campus Marketplace",
                  "target_users": ["student", "admin"],
                  "core_features": [
                    {"name": "Product publishing", "description": "Publish products.", "priority": "MUST"}
                  ],
                  "user_stories": [
                    {"role": "student", "goal": "publish a textbook", "benefit": "find a buyer", "acceptance_criteria": ["Product is saved."]}
                  ],
                  "business_boundaries": ["No escrow."],
                  "non_functional_requirements": ["Record agent outputs."],
                  "risks": ["Unsafe listings."]
                }
                """;
        String backendJson = """
                {
                  "tables": [
                    {
                      "name": "product",
                      "description": "Product listing.",
                      "fields": [
                        {"name": "id", "type": "BIGINT", "nullable": false, "description": "Primary key."}
                      ]
                    }
                  ],
                  "apis": [
                    {
                      "method": "POST",
                      "path": "/api/products",
                      "description": "Publish product.",
                      "request_params": [{"name": "title", "type": "string", "required": true, "description": "Product title."}],
                      "response_fields": [{"name": "productId", "type": "number", "description": "Created product id."}],
                      "auth_required": true,
                      "required_roles": ["STUDENT"]
                    }
                  ]
                }
                """;
        String reviewJson = """
                {
                  "score": 80,
                  "issues": [
                    {"severity": "LOW", "issue_type": "STYLE", "description": "Minor wording issue.", "suggestion": "Rename field."},
                    {"severity": "HIGH", "issue_type": "API_COVERAGE", "description": "Missing favorite API.", "suggestion": "Add /api/favorites."}
                  ]
                }
                """;

        String markdown = markdownExportService.render(prdJson, backendJson, reviewJson);

        assertThat(markdown).contains("# Campus Marketplace");
        assertThat(markdown).contains("## PRD");
        assertThat(markdown).contains("| Role | Goal | Benefit | Acceptance Criteria |");
        assertThat(markdown).contains("## Database Design");
        assertThat(markdown).contains("### Table: product");
        assertThat(markdown).contains("## API Design");
        assertThat(markdown).contains("### POST /api/products");
        assertThat(markdown).contains("## Review Report");
        assertThat(markdown).contains("| Severity | Type | Description | Suggestion |");
        assertThat(markdown.indexOf("HIGH")).isLessThan(markdown.indexOf("LOW"));
    }
}

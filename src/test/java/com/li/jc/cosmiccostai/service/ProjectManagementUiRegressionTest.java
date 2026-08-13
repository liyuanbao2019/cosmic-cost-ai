package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectManagementUiRegressionTest {

    @Test
    void managementLinksUseNativeBlankTargets() throws Exception {
        String index = read("src/main/resources/static/index.html");
        String projects = read("src/main/resources/static/projects.html");
        String models = read("src/main/resources/static/models.html");

        assertTrue(index.contains("href=\"/models.html\" target=\"_blank\" rel=\"noopener noreferrer\""));
        assertTrue(index.contains("href=\"/projects.html\" target=\"_blank\" rel=\"noopener noreferrer\""));
        assertTrue(projects.contains("href=\"/models.html\" target=\"_blank\" rel=\"noopener noreferrer\""));
        assertTrue(models.contains("href=\"/projects.html\" target=\"_blank\" rel=\"noopener noreferrer\""));
        assertFalse(index.contains("window.open('/models.html'"));
        assertFalse(index.contains("window.open('/projects.html'"));
    }

    @Test
    void statisticCardsUseFilteredAndCurrentPageCounts() throws Exception {
        String projects = read("src/main/resources/static/projects.html");
        assertFalse(projects.contains("项目总数"));
        assertTrue(projects.contains("<span>符合条件</span>"));
        assertTrue(projects.contains("<span>当前页</span>"));
        assertTrue(projects.contains("$(\"projectTotal\").textContent = filteredTotal"));
        assertTrue(projects.contains("$(\"projectVisible\").textContent = allProjects.length"));
    }

    @Test
    void requirementAndProjectLevelManagementAreSeparated() throws Exception {
        String projects = read("src/main/resources/static/projects.html");

        assertTrue(projects.contains("需求级拆分") && projects.contains("项目级材料"));
        assertTrue(projects.contains("splitLevel: \"REQUIREMENT\""));
        assertTrue(projects.contains("整合为项目级材料"));
        assertTrue(projects.contains("/api/projects/batch-split/project-groups"));
        assertTrue(projects.contains("projectGroupCreateDialogOverlay")
                && projects.contains("reuseCompletedResults: true"));
        assertFalse(projects.contains("targetBatchId"));
        assertTrue(projects.contains("再次确认") && projects.contains("全部成员需求、拆分结果"));
        assertTrue(projects.contains("project-group-id-button") && projects.contains("openProjectGroup"));
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

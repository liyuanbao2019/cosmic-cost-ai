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

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

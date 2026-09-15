package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewTablePaginationUiRegressionTest {

    @Test
    void largeReviewTablesRenderOnlyTheCurrentPage() throws Exception {
        String html = new String(Files.readAllBytes(
                Paths.get("src/main/resources/static/index.html")), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"reviewPagination\""));
        assertTrue(html.contains("let reviewPageSize = 50;"));
        assertTrue(html.contains("const visibleRows = currentReviewPageEntries(filteredRows);"));
        assertTrue(html.contains("rows.slice(start, start + reviewPageSize)"));
        assertTrue(html.contains("const fragment = document.createDocumentFragment();"));
        assertTrue(html.contains("选择当前页 "));

        int addTextCellStart = html.indexOf("function addTextCell");
        int addRowNumberCellStart = html.indexOf("function addRowNumberCell", addTextCellStart);
        String addTextCell = html.substring(addTextCellStart, addRowNumberCellStart);
        assertFalse(addTextCell.contains("window.requestAnimationFrame"));
    }
}

package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.SmartSplitJobResponse;
import com.li.jc.cosmiccostai.dto.SmartSplitStartRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleSmartSplitServiceTest {

    @TempDir
    Path temporaryProject;

    @Test
    void persistsSingleDemandProgressAndUsesSharedPipeline() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        SmartSplitPipelineService pipeline = mock(SmartSplitPipelineService.class);
        when(storage.resolveProjectDir("P1")).thenReturn(temporaryProject);
        when(pipeline.runDocument(eq("P1"), eq(""), any(SmartSplitPipelineService.StageListener.class)))
                .thenAnswer(invocation -> {
                    SmartSplitPipelineService.StageListener listener = invocation.getArgument(2);
                    listener.onStage("REUSE_ADJUSTING", 62, "正在调整复用度");
                    return new SmartSplitPipelineService.PipelineResult();
                });
        SingleSmartSplitService service = new SingleSmartSplitService(
                storage, pipeline, new SyncTaskExecutor());
        SmartSplitStartRequest request = new SmartSplitStartRequest();
        request.setSourceMode("DOCUMENT");

        SmartSplitJobResponse started = service.start("P1", request);
        SmartSplitJobResponse saved = service.get("P1");

        assertEquals("COMPLETED", saved.getStatus());
        assertEquals("DISABLED", saved.getDeepSplitStatus());
        assertEquals(100, saved.getProgressPercent());
        assertEquals(started.getJobId(), saved.getJobId());
        assertTrue(Files.exists(temporaryProject.resolve("smart-split/state.json")));
        verify(pipeline).runDocument(eq("P1"), eq(""), any(SmartSplitPipelineService.StageListener.class));
    }
}

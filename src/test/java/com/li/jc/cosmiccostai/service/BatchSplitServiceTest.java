package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.dto.ParseResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchSplitServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;
    private DocumentParseService documentParseService;
    private DocumentChapterSelectionService chapterSelectionService;
    private RequirementInputService requirementInputService;
    private SmartSplitPipelineService smartSplitPipelineService;
    private ProjectMaterialExportService projectMaterialExportService;
    private BatchSplitService service;
    private ProjectMetadata metadata;

    @BeforeEach
    void setUp() throws Exception {
        fileStorageService = mock(FileStorageService.class);
        documentParseService = mock(DocumentParseService.class);
        chapterSelectionService = mock(DocumentChapterSelectionService.class);
        smartSplitPipelineService = mock(SmartSplitPipelineService.class);
        projectMaterialExportService = mock(ProjectMaterialExportService.class);

        metadata = new ProjectMetadata();
        metadata.setId("P20260811000000ABCDEF");
        metadata.setName("测试项目");
        metadata.setSourceFileName("需求说明.docx");
        metadata.setFileType("docx");
        metadata.setStatus("UPLOADED");
        when(fileStorageService.loadMetadata(metadata.getId())).thenReturn(metadata);
        when(fileStorageService.resolveProjectDir(metadata.getId())).thenReturn(tempDir.resolve("project"));
        Files.createDirectories(tempDir.resolve("project"));

        ParseResponse parsed = new ParseResponse();
        parsed.setRequirementRecognized(true);
        when(documentParseService.parseProject(metadata.getId())).thenReturn(parsed);
        when(chapterSelectionService.get(metadata.getId())).thenReturn(new DocumentChapterSelectionResponse());

        requirementInputService = new RequirementInputService(fileStorageService);
        when(smartSplitPipelineService.runDocument(eq(metadata.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class)))
                .thenReturn(new SmartSplitPipelineService.PipelineResult());

        service = new BatchSplitService(fileStorageService, documentParseService, chapterSelectionService,
                requirementInputService, smartSplitPipelineService, projectMaterialExportService,
                new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString(), 10);
    }

    @Test
    void refusesToStartUntilEveryInputIsConfirmed() {
        BatchSplitJobResponse created = service.create(createRequest());

        assertEquals("AWAITING_CONFIRMATION", created.getStatus());
        assertEquals(1, created.getAwaitingConfirmation());
        assertThrows(BusinessException.class, () -> service.start(created.getBatchId()));

        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("4. 功能需求\n查询终端状态");
        BatchSplitJobResponse ready = service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        assertEquals("READY", ready.getStatus());
        assertEquals(1, ready.getConfirmed());

        BatchSplitJobResponse completed = service.start(created.getBatchId());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(1, completed.getCompleted());
    }

    @Test
    void invalidatesConfirmationWhenCurrentInputChangesBeforeStart() throws Exception {
        BatchSplitJobResponse created = service.create(createRequest());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);

        Files.write(java.nio.file.Paths.get(metadata.getRequirementDetailPath()),
                "确认后被修改".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> service.start(created.getBatchId()));
        BatchSplitJobResponse state = service.get(created.getBatchId());
        assertEquals("AWAITING_CONFIRMATION", state.getStatus());
        assertEquals(1, state.getAwaitingConfirmation());
    }

    @Test
    void queuedPreparationStopsAfterServiceRestartUntilManualRecovery() {
        TaskExecutor pausedExecutor = new TaskExecutor() {
            @Override
            public void execute(Runnable task) {
                // Simulate a task accepted by a process that stops before it can run.
            }
        };
        BatchSplitService firstProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, pausedExecutor, new SyncTaskExecutor(),
                3, 2, tempDir.toString(), 10);
        BatchSplitJobResponse created = firstProcess.create(createRequest());
        assertEquals("PREPARING", created.getStatus());

        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString(), 10);
        restartedProcess.recoverPersistedBatches();

        BatchSplitJobResponse recovered = restartedProcess.get(created.getBatchId());
        assertEquals("AWAITING_CONFIRMATION", recovered.getStatus());
        assertEquals(1, recovered.getFailed());
        assertEquals(0, recovered.getAwaitingConfirmation());
        assertEquals("RESTART_INTERRUPTED", recovered.getItems().get(0).getStage());

        BatchSplitJobResponse manuallyRecovered = restartedProcess.retryFailed(created.getBatchId());
        assertEquals("AWAITING_CONFIRMATION", manuallyRecovered.getStatus());
        assertEquals(0, manuallyRecovered.getFailed());
        assertEquals(1, manuallyRecovered.getAwaitingConfirmation());
        assertEquals("REVIEW_AI_INPUT", manuallyRecovered.getItems().get(0).getStage());
    }

    @Test
    void documentPreparationDoesNotWaitForBusyAiSplitWorkers() throws Exception {
        ProjectMetadata second = registerSecondProject();
        PausedTaskExecutor busySplitExecutor = new PausedTaskExecutor();
        BatchSplitService isolatedPoolsService = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), busySplitExecutor,
                3, 2, tempDir.toString(), 10);

        BatchSplitJobResponse first = isolatedPoolsService.create(createRequest());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        isolatedPoolsService.confirm(first.getBatchId(), metadata.getId(), confirmation);
        BatchSplitJobResponse firstQueued = isolatedPoolsService.start(first.getBatchId());
        assertEquals(1, firstQueued.getQueued());

        BatchSplitCreateRequest secondRequest = new BatchSplitCreateRequest();
        secondRequest.setProjectIds(Arrays.asList(second.getId()));
        BatchSplitJobResponse preparedWhileAiBusy = isolatedPoolsService.create(secondRequest);

        assertEquals("AWAITING_CONFIRMATION", preparedWhileAiBusy.getStatus());
        assertEquals(1, preparedWhileAiBusy.getAwaitingConfirmation());
        assertEquals(0, preparedWhileAiBusy.getParseQueued());
        assertEquals(0, preparedWhileAiBusy.getPreparing());
    }

    @Test
    void newerBatchGetsExecutionSlotWhileOlderBatchStillHasQueuedItems() throws Exception {
        ProjectMetadata second = registerSecondProject();
        ProjectMetadata third = registerProject("P20260811000000THIRD", "第三个需求.docx");
        ProjectMetadata fourth = registerProject("P20260811000000FOURTH", "第四个需求.docx");
        PausedTaskExecutor pausedExecutor = new PausedTaskExecutor();
        BatchSplitService fairService = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), pausedExecutor,
                3, 2, tempDir.toString(), 10);

        BatchSplitCreateRequest oldRequest = new BatchSplitCreateRequest();
        oldRequest.setProjectIds(Arrays.asList(metadata.getId(), second.getId(), third.getId()));
        BatchSplitJobResponse oldBatch = fairService.create(oldRequest);
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        fairService.confirm(oldBatch.getBatchId(), metadata.getId(), confirmation);
        fairService.confirm(oldBatch.getBatchId(), second.getId(), confirmation);
        fairService.confirm(oldBatch.getBatchId(), third.getId(), confirmation);
        fairService.start(oldBatch.getBatchId());
        assertEquals(2, pausedExecutor.size());

        BatchSplitCreateRequest newRequest = new BatchSplitCreateRequest();
        newRequest.setProjectIds(Arrays.asList(fourth.getId()));
        BatchSplitJobResponse newBatch = fairService.create(newRequest);
        fairService.confirm(newBatch.getBatchId(), fourth.getId(), confirmation);
        fairService.start(newBatch.getBatchId());

        assertEquals(3, pausedExecutor.size());
        pausedExecutor.runAt(2);
        BatchSplitJobResponse completedNewBatch = fairService.get(newBatch.getBatchId());
        assertEquals("COMPLETED", completedNewBatch.getStatus());
        assertEquals(1, completedNewBatch.getCompleted());
        assertEquals(3, fairService.get(oldBatch.getBatchId()).getQueued());
    }

    @Test
    void restartRecoveryDoesNotDispatchAnyOldOrNewBatchUntilManualRecovery() throws Exception {
        ProjectMetadata second = registerSecondProject();
        ProjectMetadata third = registerProject("P20260811000000THIRD", "第三个需求.docx");
        ProjectMetadata fourth = registerProject("P20260811000000FOURTH", "第四个需求.docx");
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");

        PausedTaskExecutor stoppedExecutor = new PausedTaskExecutor();
        BatchSplitService stoppedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), stoppedExecutor,
                3, 2, tempDir.toString(), 10);

        BatchSplitCreateRequest oldRequest = new BatchSplitCreateRequest();
        oldRequest.setProjectIds(Arrays.asList(metadata.getId(), second.getId(), third.getId()));
        BatchSplitJobResponse oldBatch = stoppedProcess.create(oldRequest);
        stoppedProcess.confirm(oldBatch.getBatchId(), metadata.getId(), confirmation);
        stoppedProcess.confirm(oldBatch.getBatchId(), second.getId(), confirmation);
        stoppedProcess.confirm(oldBatch.getBatchId(), third.getId(), confirmation);
        stoppedProcess.start(oldBatch.getBatchId());

        BatchSplitCreateRequest newRequest = new BatchSplitCreateRequest();
        newRequest.setProjectIds(Arrays.asList(fourth.getId()));
        BatchSplitJobResponse newBatch = stoppedProcess.create(newRequest);
        stoppedProcess.confirm(newBatch.getBatchId(), fourth.getId(), confirmation);
        stoppedProcess.start(newBatch.getBatchId());
        assertEquals(3, stoppedExecutor.size());

        PausedTaskExecutor recoveredExecutor = new PausedTaskExecutor();
        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), recoveredExecutor,
                3, 2, tempDir.toString(), 10);
        restartedProcess.recoverPersistedBatches();

        assertEquals(0, recoveredExecutor.size());
        assertEquals(3, restartedProcess.get(oldBatch.getBatchId()).getFailed());
        assertEquals(1, restartedProcess.get(newBatch.getBatchId()).getFailed());

        restartedProcess.retryFailed(newBatch.getBatchId());
        assertEquals(1, recoveredExecutor.size());
        assertEquals(3, restartedProcess.get(oldBatch.getBatchId()).getFailed());
        recoveredExecutor.runNext();
        assertEquals("COMPLETED", restartedProcess.get(newBatch.getBatchId()).getStatus());
        assertEquals(1, restartedProcess.get(newBatch.getBatchId()).getCompleted());
    }

    @Test
    void queuedExecutionStopsAfterServiceRestartUntilManualRecovery() {
        BatchSplitJobResponse created = service.create(createRequest());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);

        PausedTaskExecutor pausedExecutor = new PausedTaskExecutor();
        BatchSplitService stoppedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), pausedExecutor,
                3, 2, tempDir.toString(), 10);
        BatchSplitJobResponse queued = stoppedProcess.start(created.getBatchId());
        assertEquals(1, queued.getQueued());
        assertEquals(0, queued.getRunning());

        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString(), 10);
        restartedProcess.recoverPersistedBatches();

        BatchSplitJobResponse recovered = restartedProcess.get(created.getBatchId());
        assertEquals("PARTIAL_COMPLETED", recovered.getStatus());
        assertEquals(0, recovered.getCompleted());
        assertEquals(1, recovered.getFailed());
        assertEquals("RESTART_INTERRUPTED", recovered.getItems().get(0).getStage());

        BatchSplitJobResponse manuallyRecovered = restartedProcess.retryFailed(created.getBatchId());
        assertEquals("COMPLETED", manuallyRecovered.getStatus());
        assertEquals(1, manuallyRecovered.getCompleted());
        assertEquals(0, manuallyRecovered.getFailed());
    }

    @Test
    void failedItemCanRetryWhileOtherDocumentsRemainQueued() throws Exception {
        ProjectMetadata second = registerSecondProject();
        SmartSplitPipelineService.PipelineResult recoveredResult =
                new SmartSplitPipelineService.PipelineResult();
        when(smartSplitPipelineService.runDocument(eq(metadata.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class)))
                .thenThrow(new BusinessException("模拟单项失败"))
                .thenReturn(recoveredResult);

        BatchSplitCreateRequest request = new BatchSplitCreateRequest();
        request.setSplitLevel("PROJECT");
        request.setProjectIds(Arrays.asList(metadata.getId(), second.getId()));
        BatchSplitJobResponse created = service.create(request);
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        service.confirm(created.getBatchId(), second.getId(), confirmation);

        PausedTaskExecutor pausedExecutor = new PausedTaskExecutor();
        BatchSplitService runningService = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), pausedExecutor,
                3, 2, tempDir.toString(), 10);
        runningService.start(created.getBatchId());
        pausedExecutor.runNext();

        BatchSplitJobResponse partialRunning = runningService.get(created.getBatchId());
        assertEquals("RUNNING", partialRunning.getStatus());
        assertEquals(1, partialRunning.getFailed());
        assertEquals(1, partialRunning.getQueued());
        assertEquals("BLOCKED", partialRunning.getProjectMaterialStatus());

        BatchSplitJobResponse retried = runningService.retryFailed(created.getBatchId());
        assertEquals("RUNNING", retried.getStatus());
        assertEquals(0, retried.getFailed());
        assertEquals(2, retried.getQueued());
        assertEquals("PENDING", retried.getProjectMaterialStatus());
    }

    @Test
    void projectLevelGeneratesMaterialsAfterAllDocumentsComplete() {
        ProjectMaterialExportService.ProjectMaterialBundle bundle =
                mock(ProjectMaterialExportService.ProjectMaterialBundle.class);
        when(bundle.getFiles()).thenReturn(Collections.emptyMap());
        when(bundle.getRequirementCount()).thenReturn(3);
        when(bundle.getTotalCfp()).thenReturn(12D);
        when(projectMaterialExportService.export(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList())).thenReturn(bundle);

        BatchSplitCreateRequest request = createRequest();
        request.setSplitLevel("PROJECT");
        BatchSplitJobResponse created = service.create(request);
        assertEquals("PROJECT", created.getSplitLevel());
        assertEquals("PENDING", created.getProjectMaterialStatus());

        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("4. 功能需求\n查询终端状态");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        BatchSplitJobResponse completed = service.start(created.getBatchId());

        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("COMPLETED", completed.getProjectMaterialStatus());
        assertEquals(3, completed.getProjectMaterialRequirementCount());
        assertEquals(12D, completed.getProjectMaterialTotalCfp());
        verify(projectMaterialExportService).export(eq(created.getBatchId()),
                eq(Arrays.asList(metadata.getId())));
    }

    @Test
    void projectLevelKeepsSuccessfulResultsAndBlocksMaterialsUntilFailedItemRetrySucceeds() throws Exception {
        ProjectMetadata second = new ProjectMetadata();
        second.setId("P20260811000000SECOND");
        second.setName("第二个需求");
        second.setSourceFileName("第二个需求.docx");
        second.setFileType("docx");
        second.setStatus("UPLOADED");
        Path secondProjectDir = tempDir.resolve("second-project");
        Files.createDirectories(secondProjectDir);
        when(fileStorageService.loadMetadata(second.getId())).thenReturn(second);
        when(fileStorageService.resolveProjectDir(second.getId())).thenReturn(secondProjectDir);

        ParseResponse secondParsed = new ParseResponse();
        secondParsed.setRequirementRecognized(true);
        when(documentParseService.parseProject(second.getId())).thenReturn(secondParsed);
        when(chapterSelectionService.get(second.getId())).thenReturn(new DocumentChapterSelectionResponse());
        when(smartSplitPipelineService.runDocument(eq(second.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class)))
                .thenThrow(new BusinessException("模拟拆分失败"))
                .thenReturn(new SmartSplitPipelineService.PipelineResult());

        ProjectMaterialExportService.ProjectMaterialBundle bundle =
                mock(ProjectMaterialExportService.ProjectMaterialBundle.class);
        when(bundle.getFiles()).thenReturn(Collections.emptyMap());
        when(bundle.getRequirementCount()).thenReturn(2);
        when(bundle.getTotalCfp()).thenReturn(20D);
        when(projectMaterialExportService.export(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList())).thenReturn(bundle);

        BatchSplitCreateRequest request = new BatchSplitCreateRequest();
        request.setSplitLevel("PROJECT");
        request.setProjectIds(Arrays.asList(metadata.getId(), second.getId()));
        BatchSplitJobResponse created = service.create(request);

        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("已确认的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        service.confirm(created.getBatchId(), second.getId(), confirmation);

        BatchSplitJobResponse partial = service.start(created.getBatchId());
        assertEquals("PARTIAL_COMPLETED", partial.getStatus());
        assertEquals(1, partial.getCompleted());
        assertEquals(1, partial.getFailed());
        assertEquals("BLOCKED", partial.getProjectMaterialStatus());
        verify(projectMaterialExportService, never()).export(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String>anyList());

        BatchSplitJobResponse completed = service.retryFailed(created.getBatchId());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(2, completed.getCompleted());
        assertEquals(0, completed.getFailed());
        assertEquals("COMPLETED", completed.getProjectMaterialStatus());
        verify(projectMaterialExportService).export(eq(created.getBatchId()),
                eq(Arrays.asList(metadata.getId(), second.getId())));
    }

    private BatchSplitCreateRequest createRequest() {
        BatchSplitCreateRequest request = new BatchSplitCreateRequest();
        request.setProjectIds(Arrays.asList(metadata.getId()));
        return request;
    }

    private ProjectMetadata registerSecondProject() throws Exception {
        return registerProject("P20260811000000SECOND", "第二个需求.docx");
    }

    private ProjectMetadata registerProject(String projectId, String sourceFileName) throws Exception {
        ProjectMetadata second = new ProjectMetadata();
        second.setId(projectId);
        second.setName(sourceFileName.replace(".docx", ""));
        second.setSourceFileName(sourceFileName);
        second.setFileType("docx");
        second.setStatus("UPLOADED");
        Path secondProjectDir = tempDir.resolve(projectId);
        Files.createDirectories(secondProjectDir);
        when(fileStorageService.loadMetadata(second.getId())).thenReturn(second);
        when(fileStorageService.resolveProjectDir(second.getId())).thenReturn(secondProjectDir);
        ParseResponse secondParsed = new ParseResponse();
        secondParsed.setRequirementRecognized(true);
        when(documentParseService.parseProject(second.getId())).thenReturn(secondParsed);
        when(chapterSelectionService.get(second.getId())).thenReturn(new DocumentChapterSelectionResponse());
        when(smartSplitPipelineService.runDocument(eq(second.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class)))
                .thenReturn(new SmartSplitPipelineService.PipelineResult());
        return second;
    }

    private static class PausedTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.remove(0).run();
        }

        private void runAt(int index) {
            tasks.remove(index).run();
        }

        private int size() {
            return tasks.size();
        }
    }
}

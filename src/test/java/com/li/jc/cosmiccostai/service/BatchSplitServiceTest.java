package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitItemsRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryResponse;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.dto.ParseResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                3, 2, tempDir.toString());
    }

    @Test
    void acceptsMoreThanFiftyRequirementsInOneBatch() throws Exception {
        List<String> projectIds = new ArrayList<String>();
        for (int i = 1; i <= 60; i++) {
            String projectId = String.format("P20260815BULK%04d", i);
            registerProject(projectId, String.format("%02d.批量需求.docx", i));
            projectIds.add(projectId);
        }
        PausedTaskExecutor preparationExecutor = new PausedTaskExecutor();
        BatchSplitService unlimitedService = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, preparationExecutor, new SyncTaskExecutor(),
                50, 50, tempDir.toString());
        BatchSplitCreateRequest request = new BatchSplitCreateRequest();
        request.setProjectIds(projectIds);

        BatchSplitJobResponse created = unlimitedService.create(request);

        assertEquals(60, created.getTotal());
        assertEquals(60, created.getParseQueued());
        assertEquals(60, preparationExecutor.size());
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
    void confirmingValidSelectedContentClearsTheEarlyParseWarning() {
        ParseResponse parsedWithWarning = new ParseResponse();
        parsedWithWarning.setRequirementRecognized(false);
        parsedWithWarning.setRequirementWarning("未识别到有效的需求内容。");
        when(documentParseService.parseProject(metadata.getId())).thenReturn(parsedWithWarning);

        BatchSplitJobResponse created = service.create(createRequest());
        assertEquals("未识别到有效的需求内容。", created.getItems().get(0).getRequirementWarning());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("功能说明\n查询并导出业务数据");

        BatchSplitJobResponse confirmed = service.confirm(created.getBatchId(), metadata.getId(), confirmation);

        assertEquals(null, confirmed.getItems().get(0).getRequirementWarning());
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
                3, 2, tempDir.toString());
        BatchSplitJobResponse created = firstProcess.create(createRequest());
        assertEquals("PREPARING", created.getStatus());

        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString());
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
    void missingHistoricalProjectDoesNotPreventServiceStartup() {
        TaskExecutor pausedExecutor = new TaskExecutor() {
            @Override
            public void execute(Runnable task) {
                // Persist an unfinished project-level batch without running preparation.
            }
        };
        BatchSplitService firstProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, pausedExecutor, new SyncTaskExecutor(),
                3, 2, tempDir.toString());
        BatchSplitCreateRequest request = createRequest();
        request.setSplitLevel("PROJECT");
        BatchSplitJobResponse created = firstProcess.create(request);

        when(fileStorageService.loadMetadata(metadata.getId()))
                .thenThrow(new BusinessException("Project not found: " + metadata.getId()));
        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString());

        restartedProcess.recoverPersistedBatches();

        BatchSplitJobResponse recovered = restartedProcess.get(created.getBatchId());
        assertEquals(1, recovered.getFailed());
        assertEquals("PROJECT_MISSING", recovered.getItems().get(0).getStage());
        assertEquals("BLOCKED", recovered.getProjectMaterialStatus());
    }

    @Test
    void documentPreparationDoesNotWaitForBusyAiSplitWorkers() throws Exception {
        ProjectMetadata second = registerSecondProject();
        PausedTaskExecutor busySplitExecutor = new PausedTaskExecutor();
        BatchSplitService isolatedPoolsService = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), busySplitExecutor,
                3, 2, tempDir.toString());

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
                3, 2, tempDir.toString());

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
                3, 2, tempDir.toString());

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
                3, 2, tempDir.toString());
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
                3, 2, tempDir.toString());
        BatchSplitJobResponse queued = stoppedProcess.start(created.getBatchId());
        assertEquals(1, queued.getQueued());
        assertEquals(0, queued.getRunning());

        BatchSplitService restartedProcess = new BatchSplitService(fileStorageService, documentParseService,
                chapterSelectionService, requirementInputService, smartSplitPipelineService,
                projectMaterialExportService, new SyncTaskExecutor(), new SyncTaskExecutor(),
                3, 2, tempDir.toString());
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
                3, 2, tempDir.toString());
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
        verify(projectMaterialExportService).export(eq(created.getProjectGroupId() + "-V1"),
                eq(Arrays.asList(metadata.getId())));
    }

    @Test
    void projectRequirementArchiveStillCompletesWhenAggregateMaterialFails() {
        when(projectMaterialExportService.export(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList()))
                .thenThrow(new BusinessException("模拟项目整合包失败"));
        ProjectMaterialExportService.RequirementBatchArchive requirementArchive =
                mock(ProjectMaterialExportService.RequirementBatchArchive.class);
        when(requirementArchive.getArchivePath()).thenReturn(tempDir.resolve("独立生成的单需求总包.zip"));
        when(requirementArchive.getSuccessCount()).thenReturn(1);
        when(requirementArchive.getFailedProjects()).thenReturn(Collections.<String>emptyList());
        when(projectMaterialExportService.exportRequirementArchives(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class)))
                .thenReturn(requirementArchive);

        BatchSplitCreateRequest request = createRequest();
        request.setSplitLevel("PROJECT");
        BatchSplitJobResponse created = service.create(request);
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("4. 功能需求\n查询终端状态");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);

        BatchSplitJobResponse completed = service.start(created.getBatchId());

        assertEquals("MATERIAL_FAILED", completed.getStatus());
        assertEquals("FAILED", completed.getProjectMaterialStatus());
        assertEquals("COMPLETED", completed.getRequirementMaterialStatus());
        assertEquals(1, completed.getRequirementMaterialSuccessCount());
        verify(projectMaterialExportService).exportRequirementArchives(eq(created.getBatchId()),
                eq(Arrays.asList(metadata.getId())),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class));
        verify(projectMaterialExportService, never()).exportExistingRequirementArchives(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class));
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
        verify(projectMaterialExportService).export(eq(created.getProjectGroupId() + "-V1"),
                eq(Arrays.asList(metadata.getId(), second.getId())));
    }

    @Test
    void completedRequirementCanCreateProjectGroupWithoutRerunningAi() throws Exception {
        markSplitResultReady(metadata, "rows-1.json");
        stubMaterialBundle(1, 8D);

        BatchSplitCreateRequest request = createRequest();
        request.setProjectGroupName("测试项目级项目");
        BatchSplitJobResponse group = service.createProjectGroup(request);

        assertTrue(group.getProjectGroupId().startsWith("PJ"));
        assertEquals("测试项目级项目", group.getProjectGroupName());
        assertEquals("COMPLETED", group.getStatus());
        assertEquals(1, group.getCompleted());
        assertEquals(1, group.getProjectMaterialVersion());
        assertEquals("COMPLETED", group.getRequirementMaterialStatus());
        assertEquals(1, group.getRequirementMaterialSuccessCount());
        assertTrue(group.getRequirementMaterialArchive() != null);
        assertEquals("REUSED_COMPLETED", group.getItems().get(0).getStage());
        verify(smartSplitPipelineService, never()).runDocument(eq(metadata.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class));
        verify(projectMaterialExportService).export(eq(group.getProjectGroupId() + "-V1"),
                eq(Arrays.asList(metadata.getId())));
        verify(projectMaterialExportService).exportExistingRequirementArchives(eq(group.getBatchId()),
                eq(Arrays.asList(metadata.getId())),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class));
    }

    @Test
    void deletingProjectGroupCascadesRequirementDataAndAllMaterials() throws Exception {
        markSplitResultReady(metadata, "rows-delete.json");
        stubMaterialBundle(1, 8D);
        Path storedProject = tempDir.resolve("projects").resolve(metadata.getId());
        Files.createDirectories(storedProject);
        Files.write(storedProject.resolve("metadata.json"), "{}".getBytes(StandardCharsets.UTF_8));

        BatchSplitJobResponse group = service.createProjectGroup(createRequest());
        service.deleteProjectGroup(group.getProjectGroupId());

        assertFalse(Files.exists(storedProject));
        assertThrows(BusinessException.class, () -> service.get(group.getBatchId()));
        verify(projectMaterialExportService).deleteMaterialVersions(
                eq(group.getProjectGroupId()), eq(group.getBatchId()));
        verify(projectMaterialExportService).deleteRequirementArchive(eq(group.getBatchId()));
        verify(projectMaterialExportService).deleteRequirementOutputs(
                eq(Arrays.asList(metadata.getId())));
    }

    @Test
    void requirementCannotBelongToTwoProjectGroups() throws Exception {
        markSplitResultReady(metadata, "rows-exclusive.json");
        stubMaterialBundle(1, 8D);
        BatchSplitCreateRequest request = createRequest();

        BatchSplitJobResponse first = service.createProjectGroup(request);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createProjectGroup(request));

        assertTrue(first.getProjectGroupId().startsWith("PJ"));
        assertTrue(error.getMessage().contains("已经属于项目级项目"));
    }

    @Test
    void completedRequirementCanBeAddedAndCreatesNextMaterialVersion() throws Exception {
        markSplitResultReady(metadata, "rows-1.json");
        ProjectMetadata second = registerSecondProject();
        second.setName(metadata.getName());
        markSplitResultReady(second, "rows-2.json");
        stubMaterialBundle(2, 16D);

        BatchSplitJobResponse created = service.createProjectGroup(createRequest());
        BatchSplitItemsRequest addRequest = new BatchSplitItemsRequest();
        addRequest.setProjectIds(Arrays.asList(second.getId()));
        BatchSplitJobResponse updated = service.addItems(created.getBatchId(), addRequest);

        assertEquals(created.getProjectGroupId(), updated.getProjectGroupId());
        assertEquals(2, updated.getTotal());
        assertEquals(2, updated.getCompleted());
        assertEquals(2, updated.getProjectMaterialVersion());
        verify(projectMaterialExportService).export(eq(created.getProjectGroupId() + "-V1"),
                eq(Arrays.asList(metadata.getId())));
        verify(projectMaterialExportService).export(eq(created.getProjectGroupId() + "-V2"),
                eq(Arrays.asList(metadata.getId(), second.getId())));
        verify(smartSplitPipelineService, never()).runDocument(eq(second.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class));
    }

    @Test
    void rerunOneProjectMemberKeepsOthersAndCreatesNextMaterialVersion() throws Exception {
        markSplitResultReady(metadata, "rows-1.json");
        Path input = tempDir.resolve("rerun-input.txt");
        Files.write(input, "需要重新拆分的正文".getBytes(StandardCharsets.UTF_8));
        metadata.setRequirementDetailPath(input.toString());
        stubMaterialBundle(1, 8D);

        BatchSplitJobResponse created = service.createProjectGroup(createRequest());
        BatchSplitJobResponse rerun = service.rerunItem(created.getBatchId(), metadata.getId());

        assertEquals("COMPLETED", rerun.getStatus());
        assertEquals(1, rerun.getTotal());
        assertEquals(1, rerun.getCompleted());
        assertEquals(2, rerun.getProjectMaterialVersion());
        verify(smartSplitPipelineService, times(1)).runDocument(eq(metadata.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class));
        verify(projectMaterialExportService).export(eq(created.getProjectGroupId() + "-V2"),
                eq(Arrays.asList(metadata.getId())));
    }

    @Test
    void completedRequirementBatchCanRerunOnlySelectedItem() {
        BatchSplitJobResponse created = service.create(createRequest());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("需要拆分的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        service.start(created.getBatchId());

        BatchSplitJobResponse rerun = service.rerunItem(created.getBatchId(), metadata.getId());

        assertEquals("COMPLETED", rerun.getStatus());
        assertEquals(1, rerun.getCompleted());
        verify(smartSplitPipelineService, times(2)).runDocument(eq(metadata.getId()), eq(""),
                any(SmartSplitPipelineService.StageListener.class));
        verify(projectMaterialExportService, never()).export(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String>anyList());
    }

    @Test
    void completedBatchSummaryReadsLatestFinalRowsAndTotalsCfp() throws Exception {
        BatchSplitJobResponse created = service.create(createRequest());
        BatchSplitConfirmRequest confirmation = new BatchSplitConfirmRequest();
        confirmation.setAiInput("需要拆分的需求正文");
        service.confirm(created.getBatchId(), metadata.getId(), confirmation);
        service.start(created.getBatchId());

        FunctionSplitRow first = summaryRow("查询终端", "E", 1D);
        FunctionSplitRow second = summaryRow("查询终端", "X", 1D);
        FunctionSplitRow third = summaryRow("导出终端", "E", 2D);
        Path rowsPath = tempDir.resolve("summary-final-rows.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(rowsPath.toFile(),
                Arrays.asList(first, second, third));
        metadata.setFunctionSplitRowsPath(rowsPath.toString());

        BatchSplitSummaryResponse summary = service.summary(created.getBatchId());

        assertEquals("REQUIREMENT", summary.getSplitLevel());
        assertEquals(1, summary.getCompletedCount());
        assertEquals(1, summary.getStatisticsAvailableCount());
        assertEquals(2, summary.getFunctionProcessCount());
        assertEquals(3, summary.getMovementRowCount());
        assertEquals(4D, summary.getTotalCfp());
        assertEquals(4D, summary.getItems().get(0).getTotalCfp());
        assertTrue(summary.getItems().get(0).isStatisticsAvailable());
    }

    private BatchSplitCreateRequest createRequest() {
        BatchSplitCreateRequest request = new BatchSplitCreateRequest();
        request.setProjectIds(Arrays.asList(metadata.getId()));
        return request;
    }

    private FunctionSplitRow summaryRow(String process, String movementType, double cfp) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("终端管理");
        row.setFunctionalUser("管理员");
        row.setTriggerEvent("用户操作");
        row.setFunctionProcess(process);
        row.setMovementType(movementType);
        row.setCfp(cfp);
        return row;
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

    private void markSplitResultReady(ProjectMetadata project, String fileName) throws Exception {
        Path rows = tempDir.resolve(fileName);
        Files.write(rows, "[]".getBytes(StandardCharsets.UTF_8));
        project.setFunctionSplitRowsPath(rows.toString());
    }

    private void stubMaterialBundle(int requirementCount, double totalCfp) {
        ProjectMaterialExportService.ProjectMaterialBundle bundle =
                mock(ProjectMaterialExportService.ProjectMaterialBundle.class);
        when(bundle.getFiles()).thenReturn(Collections.emptyMap());
        when(bundle.getRequirementCount()).thenReturn(requirementCount);
        when(bundle.getTotalCfp()).thenReturn(totalCfp);
        when(projectMaterialExportService.export(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList())).thenReturn(bundle);
        ProjectMaterialExportService.RequirementBatchArchive requirementArchive =
                mock(ProjectMaterialExportService.RequirementBatchArchive.class);
        when(requirementArchive.getArchivePath()).thenReturn(tempDir.resolve("全部单需求送审材料.zip"));
        when(requirementArchive.getSuccessCount()).thenReturn(requirementCount);
        when(requirementArchive.getFailedProjects()).thenReturn(Collections.<String>emptyList());
        when(projectMaterialExportService.exportRequirementArchives(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class)))
                .thenReturn(requirementArchive);
        when(projectMaterialExportService.exportExistingRequirementArchives(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(ProjectMaterialExportService.RequirementArchiveProgressListener.class)))
                .thenReturn(requirementArchive);
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

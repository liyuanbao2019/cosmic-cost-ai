package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.ExportProgressResponse;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ExportProgressService {

    private final ConcurrentMap<String, ExportProgressResponse> progressByProject =
            new ConcurrentHashMap<String, ExportProgressResponse>();

    public void startFunctionDescription(String projectId, int total) {
        ExportProgressResponse response = new ExportProgressResponse();
        response.setProjectId(projectId);
        response.setStage("FUNCTION_DESCRIPTION");
        response.setRunning(true);
        response.setTotal(Math.max(total, 0));
        response.setCompleted(0);
        response.setMessage(buildMessage(0, total, true));
        progressByProject.put(projectId, response);
    }

    public void advanceFunctionDescription(String projectId, int increment) {
        ExportProgressResponse response = progressByProject.get(projectId);
        if (response == null) {
            return;
        }
        synchronized (response) {
            int completed = Math.min(response.getTotal(), response.getCompleted() + Math.max(increment, 0));
            response.setCompleted(completed);
            response.setMessage(buildMessage(completed, response.getTotal(), true));
        }
    }

    public void finishFunctionDescription(String projectId) {
        ExportProgressResponse response = progressByProject.get(projectId);
        if (response == null) {
            return;
        }
        synchronized (response) {
            response.setCompleted(response.getTotal());
            response.setRunning(false);
            response.setMessage(buildMessage(response.getCompleted(), response.getTotal(), false));
        }
    }

    public ExportProgressResponse get(String projectId) {
        ExportProgressResponse current = progressByProject.get(projectId);
        if (current == null) {
            ExportProgressResponse empty = new ExportProgressResponse();
            empty.setProjectId(projectId);
            empty.setStage("");
            empty.setRunning(false);
            empty.setTotal(0);
            empty.setCompleted(0);
            empty.setMessage("");
            return empty;
        }
        synchronized (current) {
            ExportProgressResponse copy = new ExportProgressResponse();
            copy.setProjectId(current.getProjectId());
            copy.setStage(current.getStage());
            copy.setRunning(current.isRunning());
            copy.setTotal(current.getTotal());
            copy.setCompleted(current.getCompleted());
            copy.setMessage(current.getMessage());
            return copy;
        }
    }

    private String buildMessage(int completed, int total, boolean running) {
        if (total <= 0) {
            return running ? "正在准备调用大模型生成功能描述..." : "功能描述生成完成。";
        }
        return "正在调用大模型生成功能描述：" + completed + "/" + total;
    }
}

package com.onebuild.task;

import com.onebuild.exec.ProcessTreeKiller;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskRuntime {

    private final Map<Long, Boolean> cancelled = new ConcurrentHashMap<>();
    private final Map<Long, Process> processes = new ConcurrentHashMap<>();

    public void markCancel(Long taskId) {
        cancelled.put(taskId, Boolean.TRUE);
    }

    public boolean isCancelled(Long taskId) {
        return Boolean.TRUE.equals(cancelled.get(taskId));
    }

    public void register(Long taskId, Process process) {
        processes.put(taskId, process);
    }

    public void unregister(Long taskId, Process process) {
        if (process != null) {
            processes.remove(taskId, process);
        }
    }

    public void kill(Long taskId) {
        Process process = processes.get(taskId);
        if (process != null) {
            ProcessTreeKiller.kill(process);
        }
    }

    public void clear(Long taskId) {
        cancelled.remove(taskId);
        processes.remove(taskId);
    }
}

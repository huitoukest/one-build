package com.onebuild.startup;

import com.onebuild.task.MergeTask;
import com.onebuild.task.MergeTaskRepository;
import com.onebuild.task.ProjectLockRepository;
import com.onebuild.task.TaskStep;
import com.onebuild.task.TaskStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);
    private static final List<String> NON_TERMINAL = List.of(
            "PENDING", "MERGING", "PUSHING", "PREPARING", "EXECUTING", "VERIFYING");

    private final MergeTaskRepository taskRepository;
    private final ProjectLockRepository lockRepository;
    private final TaskStepRepository taskStepRepository;

    public StartupRecovery(MergeTaskRepository taskRepository, ProjectLockRepository lockRepository,
                           TaskStepRepository taskStepRepository) {
        this.taskRepository = taskRepository;
        this.lockRepository = lockRepository;
        this.taskStepRepository = taskStepRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<MergeTask> tasks = taskRepository.findByStatusIn(NON_TERMINAL);
        for (MergeTask task : tasks) {
            task.setStatus("INTERRUPTED");
            task.setMessage("应用重启中断");
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
        lockRepository.deleteAll();
        for (TaskStep step : taskStepRepository.findByPidIsNotNull()) {
            long pid = step.getPid();
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
            });
            step.setPid(null);
            taskStepRepository.save(step);
        }
        if (!tasks.isEmpty()) {
            log.warn("启动恢复：{} 个未终态任务标记为 INTERRUPTED，项目锁已清空", tasks.size());
        }
    }
}

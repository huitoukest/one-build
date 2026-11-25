package com.onebuild.task;

import com.onebuild.common.Masking;
import com.onebuild.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务日志写入器。
 *
 * 每步按行数与字节数双上限截断，取先到者；仅保留前面的日志。
 */
@Component
public class TaskLogWriter {

    private final TaskLogRepository taskLogRepository;
    private final AppProperties props;
    private final Map<String, AtomicLong> sizes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lines = new ConcurrentHashMap<>();

    public TaskLogWriter(TaskLogRepository taskLogRepository, AppProperties props) {
        this.taskLogRepository = taskLogRepository;
        this.props = props;
    }

    public void log(Long taskId, Integer stepIndex, String step, String level, String content) {
        if (content == null || taskId == null || taskId <= 0) {
            return;
        }
        String key = taskId + ":" + (stepIndex == null ? "-" : stepIndex);
        AtomicLong lineCounter = lines.computeIfAbsent(key, k -> new AtomicLong());
        long maxLines = props.getStep().getMaxLogLinesPerStep();
        if (lineCounter.get() >= maxLines) {
            return;
        }
        String masked = Masking.mask(content);
        AtomicLong byteCounter = sizes.computeIfAbsent(key, k -> new AtomicLong());
        long current = byteCounter.get();
        long max = props.getStep().getMaxLogBytesPerStep();
        long bytes = masked.getBytes(StandardCharsets.UTF_8).length;
        if (current >= max) {
            return;
        }
        if (current + bytes > max) {
            int allowed = (int) Math.max(0, max - current);
            byte[] data = masked.getBytes(StandardCharsets.UTF_8);
            masked = new String(data, 0, Math.min(allowed, data.length), StandardCharsets.UTF_8);
            byteCounter.set(max);
        } else {
            byteCounter.addAndGet(bytes);
        }
        lineCounter.incrementAndGet();
        TaskLog log = new TaskLog();
        log.setTaskId(taskId);
        log.setStepIndex(stepIndex);
        log.setStep(truncate(step, 128));
        log.setLevel(level == null ? "INFO" : level);
        log.setContent(masked);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    public long size(Long taskId, Integer stepIndex) {
        AtomicLong counter = sizes.get(taskId + ":" + (stepIndex == null ? "-" : stepIndex));
        return counter == null ? 0 : counter.get();
    }

    public void clear(Long taskId) {
        sizes.keySet().removeIf(key -> key.startsWith(taskId + ":"));
        lines.keySet().removeIf(key -> key.startsWith(taskId + ":"));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}

package com.onebuild.task;

import com.onebuild.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TaskLogWriterTest {

    private TaskLogRepository repository;
    private TaskLogWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(TaskLogRepository.class);
        AppProperties props = new AppProperties();
        props.getStep().setMaxLogLinesPerStep(5);
        props.getStep().setMaxLogBytesPerStep(1_000_000);
        writer = new TaskLogWriter(repository, props);
    }

    @Test
    void dropsLinesAfterLimit() {
        for (int i = 0; i < 8; i++) {
            writer.log(1L, 1, "step", "INFO", "line-" + i);
        }
        verify(repository, times(5)).save(any(TaskLog.class));
    }

    @Test
    void eachStepHasIndependentLimit() {
        for (int i = 0; i < 5; i++) {
            writer.log(2L, 1, "step", "INFO", "a-" + i);
        }
        for (int i = 0; i < 3; i++) {
            writer.log(2L, 2, "step", "INFO", "b-" + i);
        }
        verify(repository, times(8)).save(any(TaskLog.class));
    }

    @Test
    void ignoresSystemTask() {
        writer.log(-1L, 1, "step", "INFO", "line");
        verify(repository, times(0)).save(any(TaskLog.class));
        reset(repository);
    }

    @Test
    void truncatesLongStepName() {
        String longName = "s".repeat(200);
        writer.log(3L, 1, longName, "INFO", "line");
        ArgumentCaptor<TaskLog> captor = ArgumentCaptor.forClass(TaskLog.class);
        verify(repository).save(captor.capture());
        assertEquals(128, captor.getValue().getStep().length());
    }
}

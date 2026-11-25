package com.onebuild.git;

import com.onebuild.common.ApiException;
import com.onebuild.config.AppProperties;
import com.onebuild.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BranchNamingServiceTest {

    private BranchNamingService service;
    private AppProperties props;
    private Project project;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getMerge().setBranchPrefix("auto_merge/");
        props.getMerge().setMaxBranches(20);
        props.getMerge().setMaxBranchNameLength(200);
        AutoBranchRepository repository = mock(AutoBranchRepository.class);
        when(repository.findByProjectIdAndBranchName(anyLong(), anyString())).thenReturn(Optional.empty());
        service = new BranchNamingService(props, repository);
        project = new Project();
        project.setId(1L);
        project.setBaseBranch("master");
    }

    @Test
    void generatesPrefixSlugAndSixHexSuffix() {
        String name = service.compute(project, List.of("a2", "a1"));
        assertTrue(name.startsWith("auto_merge/a1_a2_"), name);
        assertEquals("auto_merge/a1_a2_".length() + 6, name.length());
        assertTrue(name.substring(name.length() - 6).matches("[0-9a-f]{6}"));
    }

    @Test
    void orderDoesNotMatter() {
        String first = service.compute(project, List.of("a1", "a2"));
        String second = service.compute(project, List.of("a2", "a1"));
        assertEquals(first, second);
    }

    @Test
    void emptySourcesRejected() {
        assertThrows(ApiException.class, () -> service.compute(project, List.of()));
    }

    @Test
    void invalidPrefixRejected() {
        props.getMerge().setBranchPrefix("auto_merge");
        assertThrows(IllegalStateException.class, () -> service.compute(project, List.of("a1")));
    }
}

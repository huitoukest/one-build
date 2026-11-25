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

class BranchNamingCollisionTest {

    private AppProperties props;
    private Project project;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getMerge().setBranchPrefix("auto_merge/");
        props.getMerge().setMaxBranchNameLength(200);
        project = new Project();
        project.setId(1L);
        project.setBaseBranch("master");
    }

    @Test
    void collisionExtendsSuffixToEight() {
        AutoBranchRepository repository = mock(AutoBranchRepository.class);
        AutoBranch conflict = new AutoBranch();
        conflict.setBaseBranch("master");
        conflict.setSourceBranches("other");
        when(repository.findByProjectIdAndBranchName(anyLong(), anyString()))
                .thenReturn(Optional.of(conflict))
                .thenReturn(Optional.empty());
        BranchNamingService service = new BranchNamingService(props, repository);
        String name = service.compute(project, List.of("a1", "a2"));
        String suffix = name.substring(name.lastIndexOf('_') + 1);
        assertEquals(8, suffix.length(), name);
    }

    @Test
    void persistentCollisionFails() {
        AutoBranchRepository repository = mock(AutoBranchRepository.class);
        AutoBranch conflict = new AutoBranch();
        conflict.setBaseBranch("master");
        conflict.setSourceBranches("other");
        when(repository.findByProjectIdAndBranchName(anyLong(), anyString()))
                .thenReturn(Optional.of(conflict));
        BranchNamingService service = new BranchNamingService(props, repository);
        assertThrows(ApiException.class, () -> service.compute(project, List.of("a1", "a2")));
    }

    @Test
    void truncatesLongSlug() {
        props.getMerge().setMaxBranchNameLength(30);
        AutoBranchRepository repository = mock(AutoBranchRepository.class);
        when(repository.findByProjectIdAndBranchName(anyLong(), anyString())).thenReturn(Optional.empty());
        BranchNamingService service = new BranchNamingService(props, repository);
        String name = service.compute(project, List.of("very-long-branch-name-one", "very-long-branch-name-two"));
        assertTrue(name.length() <= 30, name);
        assertTrue(name.startsWith("auto_merge/"), name);
        assertEquals(30, name.length());
    }
}

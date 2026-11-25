package com.onebuild.project;

import com.onebuild.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站项目定时清理。
 *
 * 每日凌晨 3 点清理超过保留天数的回收站项目。
 */
@Component
public class RecycleBinCleaner {

    private static final Logger log = LoggerFactory.getLogger(RecycleBinCleaner.class);

    private final ProjectService projectService;
    private final AppProperties props;

    public RecycleBinCleaner(ProjectService projectService, AppProperties props) {
        this.projectService = projectService;
        this.props = props;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "${one-build.scheduler.timezone:Asia/Shanghai}")
    public void clean() {
        int days = props.getProject().getRecycleRetentionDays();
        int count = projectService.purgeExpired(days);
        if (count > 0) {
            log.info("回收站清理完成，共清理 {} 个项目", count);
        }
    }
}

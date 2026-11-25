package com.onebuild.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class ProcessTreeKiller {

    private static final Logger log = LoggerFactory.getLogger(ProcessTreeKiller.class);

    private ProcessTreeKiller() {
    }

    public static void kill(Process process) {
        if (process == null) {
            return;
        }
        long pid = process.pid();
        try {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            log.debug("清理子进程失败", e);
        }
        try {
            process.destroyForcibly();
        } catch (Exception e) {
            log.debug("强制终止进程失败", e);
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win") && pid > 0) {
            try {
                Process killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F").start();
                killer.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("taskkill 失败", e);
            }
        }
    }
}

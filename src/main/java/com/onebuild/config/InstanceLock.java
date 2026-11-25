package com.onebuild.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class InstanceLock {

    private static final Logger log = LoggerFactory.getLogger(InstanceLock.class);

    private final AppProperties props;
    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;

    public InstanceLock(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void acquire() {
        Path home = Paths.get(props.getHome()).toAbsolutePath().normalize();
        Path lockFile = home.resolve(props.getInstance().getLockFile());
        try {
            raf = new RandomAccessFile(lockFile.toFile(), "rw");
            channel = raf.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("另一个 one-build 实例正在运行: " + lockFile);
            }
            log.info("已获取实例锁: {}", lockFile);
        } catch (Exception e) {
            throw new IllegalStateException("获取实例锁失败: " + lockFile + ", " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void release() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
            if (raf != null) {
                raf.close();
            }
        } catch (Exception e) {
            log.warn("释放实例锁失败", e);
        }
    }
}

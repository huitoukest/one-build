package com.onebuild.auth;

import com.onebuild.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginFailLimiter {

    private final AppProperties props;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginFailLimiter(AppProperties props) {
        this.props = props;
    }

    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil == null) {
            return false;
        }
        if (Instant.now().isBefore(attempt.lockedUntil)) {
            return true;
        }
        attempts.remove(key);
        return false;
    }

    public void recordFailure(String key) {
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());
        attempt.count++;
        if (attempt.count >= props.getSecurity().getLoginFailLimit()) {
            attempt.lockedUntil = Instant.now().plusSeconds(props.getSecurity().getLoginLockMinutes() * 60L);
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    private static class Attempt {
        private int count;
        private Instant lockedUntil;
    }
}

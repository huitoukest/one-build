package com.onebuild.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具。
 *
 * 仅用于记录操作人等非鉴权场景；未登录返回 system。
 */
public final class CurrentUser {

    public static final String SYSTEM = "system";

    private CurrentUser() {
    }

    /**
     * 获取当前登录用户名。
     *
     * @return 用户名；未登录返回 system
     */
    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return SYSTEM;
        }
        return authentication.getName();
    }
}

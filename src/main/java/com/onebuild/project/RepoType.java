package com.onebuild.project;

/**
 * 仓库地址类型。
 *
 * 本期仅 {@link #FILE_PATH} 可用；远程类型字段预留。
 */
public enum RepoType {
    FILE_PATH,
    GIT_HTTP,
    GIT_SSH;

    /**
     * 解析地址类型，非法值回退 FILE_PATH。
     *
     * @param value 原始值
     * @return 地址类型
     */
    public static RepoType of(String value) {
        if (value == null || value.isBlank()) {
            return FILE_PATH;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的地址类型: " + value);
        }
    }

    /**
     * 是否为本期可用类型。
     *
     * @return 文件路径返回 true
     */
    public boolean available() {
        return this == FILE_PATH;
    }
}

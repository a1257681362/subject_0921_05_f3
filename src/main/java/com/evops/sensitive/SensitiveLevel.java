package com.evops.sensitive;

/**
 * 敏感词级别：BLOCK 拦截（写路径直接拒绝）；MASK 打码（替换为 * 后放行）。
 * 同一词条配置多个级别时取高者，见 {@link #higher}。
 */
public enum SensitiveLevel {
    MASK,
    BLOCK;

    public static SensitiveLevel higher(SensitiveLevel a, SensitiveLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static SensitiveLevel parse(String raw) {
        if (raw == null) {
            return MASK;
        }
        String v = raw.trim().toUpperCase();
        if (v.isEmpty()) {
            return MASK;
        }
        for (SensitiveLevel level : values()) {
            if (level.name().equals(v)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知敏感词级别[" + raw + "]，仅支持 BLOCK / MASK");
    }
}

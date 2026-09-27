package com.evops.sensitive;

/**
 * 一条敏感词命中。start/end 为原文坐标（0 起，end 开区间），前端据此在原文上标记；
 * word 为原文片段（可能含插空分隔符、全角形态）。
 */
public class SensitiveHit {

    private final String word;
    private final SensitiveLevel level;
    private final int start;
    private final int end;

    public SensitiveHit(String word, SensitiveLevel level, int start, int end) {
        this.word = word;
        this.level = level;
        this.start = start;
        this.end = end;
    }

    public String getWord() {
        return word;
    }

    public String getLevel() {
        return level.name();
    }

    public SensitiveLevel levelEnum() {
        return level;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }
}

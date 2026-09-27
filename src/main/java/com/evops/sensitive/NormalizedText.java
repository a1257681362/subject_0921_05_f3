package com.evops.sensitive;

/**
 * 归一化结果：归一化文本 + 归一化下标到原文下标的映射。
 * 命中区间据此换算回原文坐标，供前端在原文上标记。
 */
public class NormalizedText {

    private final String text;
    private final int[] origIndex;

    public NormalizedText(String text, int[] origIndex) {
        this.text = text;
        this.origIndex = origIndex;
    }

    /** 归一化后的文本（分隔符已剔除） */
    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /** 归一化下标 i 对应的原文下标 */
    public int origIndex(int i) {
        return origIndex[i];
    }
}

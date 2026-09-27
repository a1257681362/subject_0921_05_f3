package com.evops.sensitive;

import java.util.Collections;
import java.util.List;

/**
 * 一段文本的扫描结果：命中明细（原文坐标）、打码文本、是否应拦截、扫描时词库版本。
 */
public class ScanOutcome {

    private final List<SensitiveHit> hits;
    private final String maskedText;
    private final boolean blocked;
    private final String dictVersion;

    private ScanOutcome(List<SensitiveHit> hits, String maskedText, boolean blocked, String dictVersion) {
        this.hits = hits;
        this.maskedText = maskedText;
        this.blocked = blocked;
        this.dictVersion = dictVersion;
    }

    public static ScanOutcome of(String original, List<SensitiveHit> hits, String dictVersion) {
        boolean blocked = false;
        for (SensitiveHit hit : hits) {
            if (hit.levelEnum() == SensitiveLevel.BLOCK) {
                blocked = true;
                break;
            }
        }
        return new ScanOutcome(
                hits == null ? Collections.emptyList() : hits,
                mask(original, hits),
                blocked,
                dictVersion);
    }

    /** 打码：命中区间覆盖的原文整段（含插空分隔符）替换为 * */
    static String mask(String original, List<SensitiveHit> hits) {
        if (original == null || hits == null || hits.isEmpty()) {
            return original;
        }
        boolean[] masked = new boolean[original.length()];
        for (SensitiveHit hit : hits) {
            for (int i = hit.getStart(); i < hit.getEnd() && i < original.length(); i++) {
                masked[i] = true;
            }
        }
        StringBuilder sb = new StringBuilder(original.length());
        for (int i = 0; i < original.length(); i++) {
            sb.append(masked[i] ? '*' : original.charAt(i));
        }
        return sb.toString();
    }

    /** 处置动作：BLOCK 拦截 / MASK 打码 / PASS 无命中 */
    public String action() {
        if (blocked) {
            return "BLOCK";
        }
        return hits.isEmpty() ? "PASS" : "MASK";
    }

    public List<SensitiveHit> getHits() {
        return hits;
    }

    public String getMaskedText() {
        return maskedText;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public String getDictVersion() {
        return dictVersion;
    }
}

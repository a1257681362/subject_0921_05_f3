package com.evops.sensitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DFA 敏感词扫描器（不可变，构建后线程安全；热更新靠整体换快照）。
 * 基于嵌套 Map 字典树：每个起始位置沿转移边走，每到一个终节点记一条命中。
 * 收集全部命中（不做贪心截断）：重叠区间合并后用于打码，任一 BLOCK 命中即触发拦截。
 */
public class DfaScanner {

    private final Node root = new Node();
    private final int wordCount;

    private DfaScanner(int wordCount) {
        this.wordCount = wordCount;
    }

    /**
     * 构建。words 的 key 为已归一化词条（构建方负责先过 {@link TextNormalizer}），
     * 空词条视为无效并跳过。
     */
    public static DfaScanner build(Map<String, SensitiveLevel> words) {
        DfaScanner scanner = new DfaScanner(words.size());
        for (Map.Entry<String, SensitiveLevel> entry : words.entrySet()) {
            String word = entry.getKey();
            if (word == null || word.isEmpty()) {
                continue;
            }
            Node node = scanner.root;
            for (int i = 0; i < word.length(); i++) {
                node = node.children.computeIfAbsent(word.charAt(i), k -> new Node());
            }
            node.level = SensitiveLevel.higher(node.level, entry.getValue());
        }
        return scanner;
    }

    /**
     * 扫描归一化文本，返回原文坐标的命中列表（按起始位置升序）。
     *
     * @param normalized 归一化文本（含原文下标映射）
     * @param original   原文（用于截取命中片段）
     */
    public List<SensitiveHit> scan(NormalizedText normalized, String original) {
        List<SensitiveHit> hits = new ArrayList<>();
        if (normalized == null || normalized.length() == 0) {
            return hits;
        }
        String text = normalized.text();
        for (int i = 0; i < text.length(); i++) {
            Node node = root;
            for (int j = i; j < text.length(); j++) {
                node = node.children.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.level != null) {
                    int start = normalized.origIndex(i);
                    int end = normalized.origIndex(j) + 1;
                    hits.add(new SensitiveHit(original.substring(start, end), node.level, start, end));
                }
            }
        }
        return hits;
    }

    public int wordCount() {
        return wordCount;
    }

    private static class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private SensitiveLevel level; // 非空即终节点
    }
}

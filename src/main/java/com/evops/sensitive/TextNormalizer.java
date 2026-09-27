package com.evops.sensitive;

/**
 * 文本归一化：先归一再匹配，消除全角半角与分隔符干扰。
 * <ul>
 *   <li>全角 ASCII（U+FF01~U+FF5E）转半角，全角空格（U+3000）转普通空格；</li>
 *   <li>英文字母统一小写；</li>
 *   <li>分隔符（空白与常见干扰标点）直接剔除——"违-禁"、"违 禁"、"违*禁" 都能命中"违禁"。</li>
 * </ul>
 * 变换逐字符 1:1 进行（剔除除外），因此能给出精确的原文下标映射。
 * 词库词条入 DFA 前同样走这里，词库写法不影响匹配。
 */
public final class TextNormalizer {

    /**
     * 视为分隔符的标点（空白由 {@link Character#isWhitespace} 覆盖）。
     * 全角标点会先转半角再落入本集合，故只需列半角形态与无半角映射的中文标点。
     */
    private static final String SEPARATORS =
            "-_*·.．,，、。/\\|~～!！?？:：;；'\"`@#$%^&()（）[]【】{}<>《》=+…—–";

    private TextNormalizer() {
    }

    public static NormalizedText normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new NormalizedText("", new int[0]);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int[] index = new int[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '！' && c <= '～') {          // U+FF01~U+FF5E 全角转半角
                c = (char) (c - 0xFEE0);
            } else if (c == '　') {               // U+3000 全角空格
                c = ' ';
            }
            if (isSeparator(c)) {
                continue;
            }
            c = Character.toLowerCase(c);
            index[sb.length()] = i;
            sb.append(c);
        }
        int[] origIndex = new int[sb.length()];
        System.arraycopy(index, 0, origIndex, 0, sb.length());
        return new NormalizedText(sb.toString(), origIndex);
    }

    /** 归一化词条（词库加载用），只取归一化文本 */
    public static String normalizeWord(String word) {
        return normalize(word).text();
    }

    public static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || SEPARATORS.indexOf(c) >= 0;
    }
}

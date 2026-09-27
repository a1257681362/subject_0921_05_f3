package com.evops.sensitive;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词库解析纯单元测试：注释、级别、缺省级别、归一化后为空跳过、同词级别取高。
 */
class SensitiveWordManagerTest {

    @Test
    void parse_lines_with_levels_and_comments() {
        Map<String, SensitiveLevel> words = SensitiveWordManager.parse(
                "# 注释行\n"
                        + "\n"
                        + "违禁词|BLOCK\n"
                        + "打码词|MASK\n"
                        + "缺省词\n"
                        + "  前后有空格  \n");
        assertEquals(SensitiveLevel.BLOCK, words.get("违禁词"));
        assertEquals(SensitiveLevel.MASK, words.get("打码词"));
        assertEquals(SensitiveLevel.MASK, words.get("缺省词"));
        assertEquals(SensitiveLevel.MASK, words.get("前后有空格"));
        assertEquals(4, words.size());
    }

    @Test
    void parse_normalizes_words_like_text() {
        // 词条带分隔符/全角，归一化后与文本侧一致
        Map<String, SensitiveLevel> words = SensitiveWordManager.parse("违 禁 词|BLOCK\nＶＩＰ\n");
        assertTrue(words.containsKey("违禁词"));
        assertTrue(words.containsKey("vip"));
    }

    @Test
    void parse_skips_words_empty_after_normalization() {
        Map<String, SensitiveLevel> words = SensitiveWordManager.parse("***|BLOCK\n正常词\n");
        assertFalse(words.containsKey(""));
        assertEquals(1, words.size());
    }

    @Test
    void duplicate_word_takes_higher_level() {
        Map<String, SensitiveLevel> words = SensitiveWordManager.parse("某词|MASK\n某词|BLOCK\n");
        assertEquals(SensitiveLevel.BLOCK, words.get("某词"));
    }

    @Test
    void unknown_level_rejected() {
        try {
            SensitiveWordManager.parse("某词|DELETE\n");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("DELETE"));
            return;
        }
        throw new AssertionError("未知级别应抛 IllegalArgumentException");
    }
}

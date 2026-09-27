package com.evops.sensitive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一化纯单元测试：全半角、大小写、分隔符剔除、原文下标映射。
 */
class TextNormalizerTest {

    @Test
    void full_width_ascii_folded_to_half_width() {
        NormalizedText result = TextNormalizer.normalize("ＷＸ１２３");
        assertEquals("wx123", result.text());
        // 1:1 变换，下标不变
        assertEquals(0, result.origIndex(0));
        assertEquals(4, result.origIndex(4));
    }

    @Test
    void letters_folded_to_lower_case() {
        assertEquals("abc", TextNormalizer.normalize("AbC").text());
    }

    @Test
    void separators_dropped_and_positions_mapped() {
        // 原  文：违 - 禁 （下标 0,1,2）
        NormalizedText result = TextNormalizer.normalize("违-禁");
        assertEquals("违禁", result.text());
        assertEquals(0, result.origIndex(0));
        assertEquals(2, result.origIndex(1));
    }

    @Test
    void whitespace_and_full_width_space_are_separators() {
        assertEquals("违禁", TextNormalizer.normalize("违 禁").text());
        assertEquals("违禁", TextNormalizer.normalize("违　禁").text());
        assertEquals("违禁", TextNormalizer.normalize("违\t禁\n").text());
    }

    @Test
    void interference_punctuation_dropped() {
        assertEquals("违禁", TextNormalizer.normalize("违*禁").text());
        assertEquals("违禁", TextNormalizer.normalize("违·禁").text());
        assertEquals("违禁", TextNormalizer.normalize("违、禁。").text());
        assertEquals("违禁", TextNormalizer.normalize("【违】禁").text());
    }

    @Test
    void full_width_punctuation_folded_then_dropped() {
        // ＊（U+FF0A）先转半角 *，再按分隔符剔除
        assertEquals("违禁", TextNormalizer.normalize("违＊禁").text());
    }

    @Test
    void empty_and_null() {
        assertEquals(0, TextNormalizer.normalize(null).length());
        assertEquals(0, TextNormalizer.normalize("").length());
        assertEquals(0, TextNormalizer.normalize("***").length());
    }

    @Test
    void separator_detection() {
        assertTrue(TextNormalizer.isSeparator(' '));
        assertTrue(TextNormalizer.isSeparator('-'));
        assertTrue(TextNormalizer.isSeparator('、'));
        assertFalse(TextNormalizer.isSeparator('违'));
        assertFalse(TextNormalizer.isSeparator('a'));
    }
}

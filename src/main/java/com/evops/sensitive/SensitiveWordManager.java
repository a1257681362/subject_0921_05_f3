package com.evops.sensitive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词库管理：加载、热更新、当前快照。
 * <p>
 * 词库文件（UTF-8）：每行 <code>词语</code> 或 <code>词语|级别</code>（级别 BLOCK / MASK，缺省 MASK），
 * <code>#</code> 开头为注释。词条入库前与待检文本走同一套归一化，全角/分隔符写法不影响匹配。
 * <p>
 * 热更新两条路：file: 路径每次扫描前懒检查 mtime，变了在锁内重建 DFA 并整体换快照
 * （{@link AtomicReference}，读侧不阻塞）；或调 {@link #reload()} 立即重载。
 * 词库版本取内容 MD5 短串，随扫描留痕落库，反向核对据此识别词库漂移。
 * 加载失败保留旧快照；首次启动即缺失则以空词库启动并记 ERROR，reload 可恢复。
 */
@Component
public class SensitiveWordManager {

    public static final String EMPTY_VERSION = "EMPTY";

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordManager.class);

    private final ResourceLoader resourceLoader;
    private final String dictPath;
    private final AtomicReference<LibrarySnapshot> snapshot =
            new AtomicReference<>(LibrarySnapshot.empty());
    /** 上次加载时文件 mtime（classpath 资源不适用，恒为 -1） */
    private long lastMtime = -1L;

    public SensitiveWordManager(ResourceLoader resourceLoader,
                                @Value("${evops.sensitive.dict-path:classpath:sensitive-words.txt}")
                                String dictPath) {
        this.resourceLoader = resourceLoader;
        this.dictPath = dictPath;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 当前扫描器（file: 路径先懒检查 mtime，变了自动重载） */
    public DfaScanner scanner() {
        maybeReload();
        return snapshot.get().scanner;
    }

    public String dictVersion() {
        return snapshot.get().version;
    }

    public DictStatus status() {
        maybeReload();
        LibrarySnapshot s = snapshot.get();
        return new DictStatus(s.version, s.scanner.wordCount(), dictPath, s.loadedAt);
    }

    /** 立即重载词库；加载失败保留旧快照并返回当前状态 */
    public synchronized DictStatus reload() {
        long mtime = -1L;
        try {
            Resource resource = resourceLoader.getResource(dictPath);
            mtime = mtimeOf(resource);
            byte[] bytes = StreamUtils.copyToByteArray(resource.getInputStream());
            Map<String, SensitiveLevel> words = parse(new String(bytes, StandardCharsets.UTF_8));
            DfaScanner scanner = DfaScanner.build(words);
            snapshot.set(new LibrarySnapshot(scanner, md5Short(bytes), LocalDateTime.now()));
            log.info("敏感词库已加载：{}，词条数={}，版本={}", dictPath, scanner.wordCount(), dictVersion());
        } catch (Exception ex) {
            if (snapshot.get().scanner.wordCount() > 0) {
                log.error("敏感词库重载失败，保留旧快照：{}，原因：{}", dictPath, ex.getMessage());
            } else {
                log.error("敏感词库加载失败，暂以空词库运行：{}，原因：{}", dictPath, ex.getMessage());
            }
        } finally {
            // 失败也记下 mtime：坏文件只报错一次，文件变更后才会重试（显式 reload 不受限）
            lastMtime = mtime;
        }
        LibrarySnapshot s = snapshot.get();
        return new DictStatus(s.version, s.scanner.wordCount(), dictPath, s.loadedAt);
    }

    /** file: 路径的懒热更新：mtime 变化才重建；classpath 资源不检查 */
    private void maybeReload() {
        if (!dictPath.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
            try {
                File file = resourceLoader.getResource(dictPath).getFile();
                long mtime = file.lastModified();
                if (mtime > 0L && mtime != lastMtime) {
                    reload();
                }
            } catch (IOException ex) {
                // 资源不可读时由 reload 记录日志，这里不重复刷屏
            }
        }
    }

    private long mtimeOf(Resource resource) {
        try {
            return resource.getFile().lastModified();
        } catch (IOException ex) {
            return -1L;
        }
    }

    /** 解析词库：空行、# 注释跳过；词条归一化后为空跳过；同词多级别取高 */
    static Map<String, SensitiveLevel> parse(String content) {
        Map<String, SensitiveLevel> words = new HashMap<>();
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String word = trimmed;
            SensitiveLevel level = SensitiveLevel.MASK;
            int pipe = trimmed.indexOf('|');
            if (pipe >= 0) {
                word = trimmed.substring(0, pipe).trim();
                level = SensitiveLevel.parse(trimmed.substring(pipe + 1));
            }
            String normalized = TextNormalizer.normalizeWord(word);
            if (normalized.isEmpty()) {
                log.warn("敏感词条归一化后为空，已跳过：[{}]", word);
                continue;
            }
            words.merge(normalized, level, SensitiveLevel::higher);
        }
        return words;
    }

    private static String md5Short(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static class LibrarySnapshot {
        private final DfaScanner scanner;
        private final String version;
        private final LocalDateTime loadedAt;

        LibrarySnapshot(DfaScanner scanner, String version, LocalDateTime loadedAt) {
            this.scanner = scanner;
            this.version = version;
            this.loadedAt = loadedAt;
        }

        static LibrarySnapshot empty() {
            return new LibrarySnapshot(DfaScanner.build(new HashMap<>()), EMPTY_VERSION, null);
        }
    }

    /** 词库状态（info / reload 接口返回） */
    public static class DictStatus {
        private final String dictVersion;
        private final int wordCount;
        private final String dictPath;
        private final LocalDateTime loadedAt;

        DictStatus(String dictVersion, int wordCount, String dictPath, LocalDateTime loadedAt) {
            this.dictVersion = dictVersion;
            this.wordCount = wordCount;
            this.dictPath = dictPath;
            this.loadedAt = loadedAt;
        }

        public String getDictVersion() {
            return dictVersion;
        }

        public int getWordCount() {
            return wordCount;
        }

        public String getDictPath() {
            return dictPath;
        }

        public LocalDateTime getLoadedAt() {
            return loadedAt;
        }
    }
}

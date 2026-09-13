package feixue.chat.server.com;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 小游戏战绩记录：每局游戏结束时追加一条记录到对应记录文件，并提供按得分排名（同名用户取历史最高分）。
// 文件格式（与 onlypd.json 的块格式一致）：
//   load[
//   id:玩家id
//   Gamesname:游戏名
//   time:结束时间(yyyy-MM-dd HH:mm:ss)
//   Score:得分
//   ];
class GameRecordStore {
    static final String SNAKE_RECORDS_FILE = "game_records.json";
    static final String CLICK_RECORDS_FILE = "buui.json";
    static final String FPS_RECORDS_FILE = "fps.json";
    private static final Set<String> ALLOWED_RECORD_FILES = new HashSet<>(
            Arrays.asList(SNAKE_RECORDS_FILE, CLICK_RECORDS_FILE, FPS_RECORDS_FILE));
    private static final Pattern BLOCK_PATTERN = Pattern.compile("(?is)load\\s*\\[(.*?)\\]\\s*;");
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?im)^\\s*(id|Gamesname|time|Score)\\s*:\\s*(.*?)\\s*$");

    private final ChatServer server;

    GameRecordStore(ChatServer server) {
        this.server = server;
    }

    boolean isAllowedFile(String fileName) {
        return fileName != null && ALLOWED_RECORD_FILES.contains(fileName);
    }

    // 保存一条游戏记录：记录文件、id（玩家昵称）、游戏名、得分；结束时间由服务器本地时间生成
    void saveRecord(String fileName, String id, String gamesName, int score) {
        if (!isAllowedFile(fileName)) {
            server.log("拒绝写入未授权的游戏记录文件: " + fileName);
            return;
        }
        String cleanId = sanitize(id);
        String cleanName = sanitize(gamesName);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String block = "load[\n"
                + "id:" + cleanId + "\n"
                + "Gamesname:" + cleanName + "\n"
                + "time:" + time + "\n"
                + "Score:" + score + "\n"
                + "];\n";
        File file = new File(fileName);
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(block);
            server.log("游戏记录已保存: " + cleanId + " 玩 " + cleanName + " 得分 " + score + "（" + time + "）");
        } catch (IOException e) {
            server.log("保存游戏记录失败: " + e.getMessage());
        }
    }

    // 按得分排名：同名用户取历史最高分，返回前 limit 名
    List<RankEntry> getTopRank(String fileName, String gamesName, int limit) {
        if (!isAllowedFile(fileName) || limit <= 0) {
            return Collections.emptyList();
        }
        File file = new File(fileName);
        if (!file.exists()) {
            return Collections.emptyList();
        }
        Map<String, Integer> bestScores = new HashMap<>();
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher blockMatcher = BLOCK_PATTERN.matcher(content);
            while (blockMatcher.find()) {
                String block = blockMatcher.group(1);
                Matcher fieldMatcher = FIELD_PATTERN.matcher(block);
                Map<String, String> fields = new HashMap<>();
                while (fieldMatcher.find()) {
                    fields.put(fieldMatcher.group(1), fieldMatcher.group(2));
                }
                if (!gamesName.equals(fields.get("Gamesname"))) {
                    continue;
                }
                String id = fields.get("id");
                String scoreText = fields.get("Score");
                if (id == null || id.isEmpty() || scoreText == null) {
                    continue;
                }
                int score;
                try {
                    score = Integer.parseInt(scoreText.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                Integer previous = bestScores.get(id);
                if (previous == null || score > previous) {
                    bestScores.put(id, score);
                }
            }
        } catch (IOException e) {
            server.log("读取游戏记录失败: " + e.getMessage());
            return Collections.emptyList();
        }

        List<RankEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : bestScores.entrySet()) {
            entries.add(new RankEntry(entry.getKey(), entry.getValue()));
        }
        Collections.sort(entries, (left, right) -> {
            int byScore = right.score - left.score;
            return byScore != 0 ? byScore : left.id.compareTo(right.id);
        });
        return entries.size() <= limit ? entries : new ArrayList<>(entries.subList(0, limit));
    }

    // 清理可能破坏 load[] 块格式的字符
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n\\[\\]:|]", "").trim();
    }

    // 排行榜条目
    static class RankEntry {
        final String id;
        final int score;

        RankEntry(String id, int score) {
            this.id = id;
            this.score = score;
        }
    }
}

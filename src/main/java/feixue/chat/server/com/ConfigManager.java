package feixue.chat.server.com;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

// 配置管理功能：onlypd.json（私有频道）、web-config.json、ssl-config.json、version-config.json 的读写与 JSON 工具
class ConfigManager {
    private final ChatServer server;

    ConfigManager(ChatServer server) {
        this.server = server;
    }

    synchronized void loadOnlyPdConfiguration(boolean force) {
        try {
            if (server.onlyPdConfigPath == null) {
                server.onlyPdConfigPath = resolveConfigPath(ChatServer.ONLY_PD_CONFIG_FILE);
            }
            if (!Files.exists(server.onlyPdConfigPath)) {
                Path parent = server.onlyPdConfigPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(server.onlyPdConfigPath, ChatServer.EMPTY_ONLY_PD_CONFIG.getBytes(StandardCharsets.UTF_8));
            }

            long modified = Files.getLastModifiedTime(server.onlyPdConfigPath).toMillis();
            long size = Files.size(server.onlyPdConfigPath);
            if (!force && modified == server.onlyPdLastModified && size == server.onlyPdLastSize) {
                return;
            }

            String content = new String(Files.readAllBytes(server.onlyPdConfigPath), StandardCharsets.UTF_8);
            Map<String, String> newPasswords = new LinkedHashMap<>();
            Map<String, String> newGroups = new LinkedHashMap<>();
            Set<String> newConfiguredGroups = new LinkedHashSet<>();
            Matcher blockMatcher = ChatServer.CHANNEL_BLOCK_PATTERN.matcher(content);
            while (blockMatcher.find()) {
                String name = null;
                String password = null;
                Matcher fieldMatcher = ChatServer.CHANNEL_FIELD_PATTERN.matcher(blockMatcher.group(1));
                while (fieldMatcher.find()) {
                    String key = fieldMatcher.group(1).toLowerCase(Locale.ROOT);
                    String value = cleanConfigValue(fieldMatcher.group(2));
                    if ("name".equals(key)) {
                        name = value;
                    } else {
                        password = value;
                    }
                }
                if (name == null || name.isEmpty() || password == null) {
                    continue;
                }
                String group = channelGroupForName(name);
                newPasswords.put(name, password);
                newGroups.put(name, group);
                if (name.toLowerCase(Locale.ROOT).endsWith("chat") && name.length() > 4) {
                    newGroups.putIfAbsent(name.substring(0, name.length() - 4), group);
                }
                newConfiguredGroups.add(group);
            }
            newGroups.put("public", ChatServer.PUBLIC_CHANNEL_GROUP);
            newGroups.put("公共", ChatServer.PUBLIC_CHANNEL_GROUP);

            Set<String> removedGroups = new LinkedHashSet<>(server.configuredChannelGroups);
            removedGroups.removeAll(newConfiguredGroups);
            server.accountPasswords = Collections.unmodifiableMap(newPasswords);
            server.accountGroups = Collections.unmodifiableMap(newGroups);
            server.configuredChannelGroups = Collections.unmodifiableSet(newConfiguredGroups);
            server.onlyPdLastModified = modified;
            server.onlyPdLastSize = size;

            for (String removedGroup : removedGroups) {
                server.voiceChannelEnabled.put(removedGroup, false);
                Set<ClientHandler> members = server.voiceRooms.get(removedGroup);
                if (members != null) {
                    for (ClientHandler member : new java.util.ArrayList<>(members)) {
                        member.sendMessage("/live_group_disabled|" + removedGroup);
                    }
                }
            }
            if (server.serverVoiceGroup != null && removedGroups.contains(server.serverVoiceGroup)) {
                server.voiceManager.stopServerVoiceSession();
            }
            if (server.ui != null && server.ui.logArea != null) {
                server.log("频道配置已刷新: " + newConfiguredGroups.size() + " 个私有频道");
                server.ui.refreshOnlineUsersPanel();
                server.voiceManager.refreshVoiceChannelPanel();
                server.ui.refreshChannelManagementPanel();
            }
        } catch (Exception e) {
            if (server.ui != null && server.ui.logArea != null) {
                server.log("读取 " + ChatServer.ONLY_PD_CONFIG_FILE + " 失败，继续使用上次配置: " + e.getMessage());
            } else {
                System.err.println("读取 " + ChatServer.ONLY_PD_CONFIG_FILE + " 失败: " + e.getMessage());
            }
        }
    }

    Path resolveConfigPath(String fileName) {
        Path workingPath = Paths.get(System.getProperty("user.dir"), fileName).toAbsolutePath().normalize();
        if (Files.exists(workingPath)) {
            return workingPath;
        }
        try {
            Path codePath = Paths.get(ChatServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path codeDirectory = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (codeDirectory != null) {
                Path besideCode = codeDirectory.resolve(fileName);
                if (Files.exists(besideCode)) {
                    return besideCode;
                }
                Path directoryName = codeDirectory.getFileName();
                if (directoryName != null && "target".equalsIgnoreCase(directoryName.toString())
                        && codeDirectory.getParent() != null) {
                    return codeDirectory.getParent().resolve(fileName);
                }
            }
        } catch (Exception ignored) {
            // 回退到服务器启动目录。
        }
        return workingPath;
    }

    String cleanConfigValue(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith(",") || cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    void loadWebConfiguration() {
        try {
            server.webConfigPath = resolveConfigPath(ChatServer.WEB_CONFIG_FILE);
            if (!Files.exists(server.webConfigPath)) {
                saveWebConfiguration(server.webVerificationQuestion, server.webVerificationAnswer);
                return;
            }
            String content = new String(Files.readAllBytes(server.webConfigPath), StandardCharsets.UTF_8);
            Matcher questionMatcher = ChatServer.WEB_QUESTION_PATTERN.matcher(content);
            Matcher answerMatcher = ChatServer.WEB_ANSWER_PATTERN.matcher(content);
            if (questionMatcher.find()) {
                String question = unescapeJsonString(questionMatcher.group(1)).trim();
                if (!question.isEmpty()) {
                    server.webVerificationQuestion = question;
                }
            }
            if (answerMatcher.find()) {
                String answer = unescapeJsonString(answerMatcher.group(1)).trim();
                if (!answer.isEmpty()) {
                    server.webVerificationAnswer = answer;
                }
            }
        } catch (Exception e) {
            System.err.println("读取 " + ChatServer.WEB_CONFIG_FILE + " 失败，使用默认验证设置: " + e.getMessage());
        }
    }

    synchronized void saveWebConfiguration(String question, String answer) throws IOException {
        if (server.webConfigPath == null) {
            server.webConfigPath = resolveConfigPath(ChatServer.WEB_CONFIG_FILE);
        }
        Path parent = server.webConfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{\n"
                + "  \"question\": \"" + escapeJsonString(question) + "\",\n"
                + "  \"answer\": \"" + escapeJsonString(answer) + "\"\n"
                + "}\n";
        Path temporary = server.webConfigPath.resolveSibling(server.webConfigPath.getFileName() + ".tmp");
        Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, server.webConfigPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, server.webConfigPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void loadSslConfiguration() {
        try {
            server.sslConfigPath = resolveConfigPath(ChatServer.SSL_CONFIG_FILE);
            if (!Files.exists(server.sslConfigPath)) {
                saveSslConfiguration();
                return;
            }
            String content = new String(Files.readAllBytes(server.sslConfigPath), StandardCharsets.UTF_8);
            Matcher enabled = ChatServer.SSL_ENABLED_PATTERN.matcher(content);
            Matcher port = ChatServer.SSL_PORT_PATTERN.matcher(content);
            Matcher certificate = ChatServer.SSL_CERTIFICATE_PATTERN.matcher(content);
            Matcher privateKey = ChatServer.SSL_PRIVATE_KEY_PATTERN.matcher(content);
            if (enabled.find()) server.sslEnabled = Boolean.parseBoolean(enabled.group(1));
            if (port.find()) {
                int configuredPort = Integer.parseInt(port.group(1));
                if (configuredPort >= 1 && configuredPort <= 65535) server.sslPort = configuredPort;
            }
            if (certificate.find()) server.sslCertificateFile = unescapeJsonString(certificate.group(1));
            if (privateKey.find()) server.sslPrivateKeyFile = unescapeJsonString(privateKey.group(1));
        } catch (Exception e) {
            server.sslEnabled = false;
            logAreaSafe("读取 " + ChatServer.SSL_CONFIG_FILE + " 失败，HTTPS/WSS 已关闭: " + e.getMessage());
        }
    }

    synchronized void saveSslConfiguration() throws IOException {
        if (server.sslConfigPath == null) server.sslConfigPath = resolveConfigPath(ChatServer.SSL_CONFIG_FILE);
        Path parent = server.sslConfigPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        String json = "{\n"
                + "  \"enabled\": " + server.sslEnabled + ",\n"
                + "  \"port\": " + server.sslPort + ",\n"
                + "  \"certificateFile\": \"" + escapeJsonString(server.sslCertificateFile) + "\",\n"
                + "  \"privateKeyFile\": \"" + escapeJsonString(server.sslPrivateKeyFile) + "\"\n"
                + "}\n";
        Path temporary = server.sslConfigPath.resolveSibling(server.sslConfigPath.getFileName() + ".tmp");
        Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, server.sslConfigPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, server.sslConfigPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void logAreaSafe(String message) {
        if (server.ui == null || server.ui.logArea == null) System.err.println(message); else server.log(message);
    }

    void loadMinimumVersionConfiguration() {
        server.minimumClientVersion = ChatServer.DEFAULT_MIN_CLIENT_VERSION;
        try {
            server.minimumVersionConfigPath = resolveConfigPath(ChatServer.MINIMUM_VERSION_CONFIG_FILE);
            if (!Files.exists(server.minimumVersionConfigPath)) {
                saveMinimumVersionConfiguration(server.minimumClientVersion);
                return;
            }
            String content = new String(Files.readAllBytes(server.minimumVersionConfigPath), StandardCharsets.UTF_8);
            Matcher matcher = ChatServer.MINIMUM_VERSION_CONFIG_PATTERN.matcher(content);
            if (!matcher.find() || !server.messageGuard.isValidVersionNumber(matcher.group(1).trim())) {
                throw new IOException("配置中的最低版本号格式无效");
            }
            server.minimumClientVersion = matcher.group(1).trim();
        } catch (Exception e) {
            System.err.println("读取 " + ChatServer.MINIMUM_VERSION_CONFIG_FILE + " 失败，使用默认版本 "
                    + ChatServer.DEFAULT_MIN_CLIENT_VERSION + ": " + e.getMessage());
        }
    }

    synchronized void saveMinimumVersionConfiguration(String version) throws IOException {
        if (server.minimumVersionConfigPath == null) {
            server.minimumVersionConfigPath = resolveConfigPath(ChatServer.MINIMUM_VERSION_CONFIG_FILE);
        }
        Path parent = server.minimumVersionConfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{\n  \"minimumClientVersion\": \"" + version + "\"\n}\n";
        Path temporary = server.minimumVersionConfigPath.resolveSibling(
                server.minimumVersionConfigPath.getFileName() + ".tmp");
        Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, server.minimumVersionConfigPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, server.minimumVersionConfigPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
            }
        }
        return escaped.toString();
    }

    String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                result.append(ch);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            result.append("\\u");
                        }
                    } else {
                        result.append("\\u");
                    }
                    break;
                default: result.append(escaped);
            }
        }
        return result.toString();
    }

    String channelGroupForName(String name) {
        String groupName = name.trim();
        if (groupName.startsWith("group_")) {
            return groupName;
        }
        if (groupName.toLowerCase(Locale.ROOT).endsWith("chat") && groupName.length() > 4) {
            groupName = groupName.substring(0, groupName.length() - 4);
        }
        return "group_" + groupName;
    }

    synchronized void saveOnlyPdConfiguration(Map<String, String> channels) throws IOException {
        if (server.onlyPdConfigPath == null) {
            server.onlyPdConfigPath = resolveConfigPath(ChatServer.ONLY_PD_CONFIG_FILE);
        }
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : channels.entrySet()) {
            content.append("new{\n")
                    .append("name@").append(entry.getKey()).append('\n')
                    .append("passworld@").append(entry.getValue()).append('\n')
                    .append("};\n");
        }
        Path parent = server.onlyPdConfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = server.onlyPdConfigPath.resolveSibling(server.onlyPdConfigPath.getFileName() + ".tmp");
        Files.write(temporary, content.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, server.onlyPdConfigPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, server.onlyPdConfigPath, StandardCopyOption.REPLACE_EXISTING);
        }
        loadOnlyPdConfiguration(true);
    }
}

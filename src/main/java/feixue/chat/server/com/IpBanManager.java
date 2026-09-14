package feixue.chat.server.com;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent manual IP bans plus short-lived connection-rate protection. */
final class IpBanManager {
    static final long CONNECTION_WINDOW_MS = 120_000L;
    static final int MAX_CONNECTIONS_PER_WINDOW = 8;
    static final long TEMPORARY_BAN_MS = 10 * 60_000L;
    private static final Pattern PERMANENT_PATTERN = Pattern.compile("\\\"permanent\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern TEMPORARY_PATTERN = Pattern.compile(
            "\\{\\s*\\\"ip\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"expiresAt\\\"\\s*:\\s*(\\d+)\\s*\\}");
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");

    enum Decision {
        ALLOWED(""), PERMANENT_BAN("永久封禁"), TEMPORARY_BAN("临时封禁"), RATE_LIMIT("连接过于频繁，已临时封禁10分钟");

        final String message;
        Decision(String message) { this.message = message; }
    }

    private final Path configPath;
    private final LongSupplier clock;
    private final Set<String> permanentBans = new HashSet<>();
    private final Map<String, Long> temporaryBans = new HashMap<>();
    private final Map<String, Deque<Long>> connectionAttempts = new HashMap<>();

    IpBanManager(Path configPath) throws IOException {
        this(configPath, System::currentTimeMillis);
    }

    IpBanManager(Path configPath, LongSupplier clock) throws IOException {
        this.configPath = configPath;
        this.clock = clock;
        load();
    }

    /** Checks only existing bans. Transport setup, HTTP resources and heartbeats must not add attempts. */
    synchronized Decision checkBlocked(String ip) {
        String normalized = normalizeIp(ip);
        if (normalized == null) return Decision.ALLOWED;
        long now = clock.getAsLong();
        boolean changed = removeExpiredTemporaryBans(now);
        if (permanentBans.contains(normalized)) {
            persistIfChanged(changed);
            return Decision.PERMANENT_BAN;
        }
        if (temporaryBans.containsKey(normalized)) {
            persistIfChanged(changed);
            return Decision.TEMPORARY_BAN;
        }
        persistIfChanged(changed);
        return Decision.ALLOWED;
    }

    /** Counts one chat admission attempt for automatic rate protection.
     * TCP handshakes and heartbeats do not call this method; private password attempts do. */
    synchronized Decision registerConnection(String ip) {
        String normalized = normalizeIp(ip);
        if (normalized == null) return Decision.ALLOWED;
        Decision blocked = checkBlocked(normalized);
        if (blocked != Decision.ALLOWED) return blocked;
        long now = clock.getAsLong();
        Deque<Long> attempts = connectionAttempts.computeIfAbsent(normalized, ignored -> new ArrayDeque<>());
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= CONNECTION_WINDOW_MS) attempts.removeFirst();
        attempts.addLast(now);
        if (attempts.size() > MAX_CONNECTIONS_PER_WINDOW) {
            temporaryBans.put(normalized, now + TEMPORARY_BAN_MS);
            attempts.clear();
            persistQuietly();
            return Decision.RATE_LIMIT;
        }
        return Decision.ALLOWED;
    }

    synchronized boolean banPermanently(String requestedIp) throws IOException {
        String ip = requireIp(requestedIp);
        boolean changed = permanentBans.add(ip);
        temporaryBans.remove(ip);
        connectionAttempts.remove(ip);
        if (changed) save();
        return changed;
    }

    synchronized boolean unban(String requestedIp) throws IOException {
        String ip = requireIp(requestedIp);
        boolean changed = permanentBans.remove(ip);
        changed |= temporaryBans.remove(ip) != null;
        connectionAttempts.remove(ip);
        if (changed) save();
        return changed;
    }

    synchronized void clearTemporaryBans() {
        if (temporaryBans.isEmpty() && connectionAttempts.isEmpty()) return;
        temporaryBans.clear();
        connectionAttempts.clear();
        persistQuietly();
    }

    synchronized boolean isPermanentlyBanned(String requestedIp) {
        String ip = normalizeIp(requestedIp);
        return ip != null && permanentBans.contains(ip);
    }

    private void load() throws IOException {
        if (!Files.exists(configPath)) {
            save();
            return;
        }
        String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        Matcher permanent = PERMANENT_PATTERN.matcher(json);
        if (permanent.find()) {
            Matcher item = JSON_STRING.matcher(permanent.group(1));
            while (item.find()) {
                String ip = normalizeIp(item.group(1));
                if (ip != null) permanentBans.add(ip);
            }
        }
        Matcher temporary = TEMPORARY_PATTERN.matcher(json);
        while (temporary.find()) {
            String ip = normalizeIp(temporary.group(1));
            try {
                long expiresAt = Long.parseLong(temporary.group(2));
                if (ip != null && expiresAt > clock.getAsLong()) temporaryBans.put(ip, expiresAt);
            } catch (NumberFormatException ignored) { }
        }
        if (removeExpiredTemporaryBans(clock.getAsLong())) save();
    }

    private boolean removeExpiredTemporaryBans(long now) {
        boolean changed = false;
        java.util.Iterator<Map.Entry<String, Long>> iterator = temporaryBans.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    private void persistIfChanged(boolean changed) {
        if (changed) persistQuietly();
    }

    private void persistQuietly() {
        try { save(); }
        catch (IOException e) { System.err.println("保存 IP 封禁配置失败: " + e.getMessage()); }
    }

    private void save() throws IOException {
        Path parent = configPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> permanent = new ArrayList<>(permanentBans);
        List<String> temporary = new ArrayList<>(temporaryBans.keySet());
        java.util.Collections.sort(permanent);
        java.util.Collections.sort(temporary);
        StringBuilder json = new StringBuilder("{\n  \"permanent\": [");
        for (int i = 0; i < permanent.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    \"").append(permanent.get(i)).append('"');
        }
        json.append(permanent.isEmpty() ? "],\n" : "\n  ],\n");
        json.append("  \"temporary\": [");
        for (int i = 0; i < temporary.size(); i++) {
            if (i > 0) json.append(',');
            String ip = temporary.get(i);
            json.append("\n    {\"ip\": \"").append(ip).append("\", \"expiresAt\": ")
                    .append(temporaryBans.get(ip)).append('}');
        }
        json.append(temporary.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Path temp = Files.createTempFile(parent, "ip-ban-", ".tmp");
        try {
            Files.write(temp, json.toString().getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String requireIp(String requestedIp) throws IOException {
        String ip = normalizeIp(requestedIp);
        if (ip == null) throw new IOException("IP 地址格式无效");
        return ip;
    }

    static String normalizeIp(String requestedIp) {
        if (requestedIp == null) return null;
        String value = requestedIp.trim();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.isEmpty() || value.length() > 45 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) return null;
        boolean ipv4 = value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        boolean ipv6 = value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:.]+") ;
        if (!ipv4 && !ipv6) return null;
        if (ipv4) {
            for (String octet : value.split("\\.")) {
                try { if (Integer.parseInt(octet) > 255) return null; }
                catch (NumberFormatException e) { return null; }
            }
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if ((ipv4 && address.getAddress().length != 4) || (ipv6 && address.getAddress().length != 16)) return null;
            return address.getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }
}

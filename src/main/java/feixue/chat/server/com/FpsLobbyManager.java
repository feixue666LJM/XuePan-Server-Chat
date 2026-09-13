package feixue.chat.server.com;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

// 3D 射击生存 · 多人模式大厅
// - 全局可见：任何群组的用户都能看到/加入任何群组用户创建的服务器
// - 每个用户最多创建一个服务器（退出后释放名额）
// - 服务器可设置密码，人数 2~16
class FpsLobbyManager {
    // 多人游戏房间使用同一张地图；客户端只渲染这个共享地图标识对应的布局。
    static final String SHARED_MAP_ID = "fps-shared-arena-v1";
    // 地图道具坐标属于共享房间地图，由服务器快照下发，客户端不再自行决定布局。
    static final String SHARED_BLOOD_POSITION = "0|0.7|0";
    static final String SHARED_AMMO_POSITION = "1.1|0.6|0";
    private static final long PICKUP_RESPAWN_MS = 60_000L;
    private static final long KILL_CREDIT_WINDOW_MS = 10_000L;
    private static final int MAX_PENDING_HITS = 4_096;
    private static final int MAX_LIFE_GENERATION = 1_000_000_000;
    private static final int MAX_BOT_ID_LENGTH = 48;
    // 服务端同时约束索引和伤害，避免伪造超额伤害。
    private static final int WEAPON_COUNT = 4;
    private static final int BOSS_HP = 10_000;
    private static final long BOSS_SPAWN_DELAY_MS = 90_000L;
    private static final long BOSS_MINION_DELAY_MS = 10_000L;
    private final ChatServer server;
    private final Map<String, FpsServerInfo> servers = new ConcurrentHashMap<>();
    private final Map<String, String> hostToServer = new ConcurrentHashMap<>();
    private final Map<String, String> memberToServer = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicLong hitSequence = new AtomicLong();

    FpsLobbyManager(ChatServer server) {
        this.server = server;
    }

    static class FpsServerInfo {
        final String serverId;
        final String hostName;
        final String password;
        final int maxPlayers;
        final Set<String> players = ConcurrentHashMap.newKeySet();
        // 新加入的玩家需要先收到房间内其他玩家的最后状态，不能只等待下一次状态广播。
        final Map<String, String> playerStates = new ConcurrentHashMap<>();
        // 普通机器人状态由房主上报，服务器保留最近快照供新成员初始化。
        final Map<String, String> botStates = new ConcurrentHashMap<>();
        // 每个命中都由服务器分配唯一编号，受害者只能以该编号确认一次死亡。
        final Map<String, PendingHit> pendingHits = new ConcurrentHashMap<>();
        // 玩家死亡后锁住该生命编号；只有编号更大的存活状态才能解除。
        final Map<String, Integer> deathLockedLifeGenerations = new ConcurrentHashMap<>();
        // 道具属于房间，不属于某个浏览器；状态由服务器统一维护。
        volatile boolean bloodVisible = true;
        volatile boolean ammoVisible = true;
        volatile long bloodRespawnAt;
        volatile long ammoRespawnAt;
        volatile boolean botsEnabled = true;
        final long createdAt = System.currentTimeMillis();
        // 房间创建即确定统一音乐时间轴，后来加入的客户端按此时间轴定位循环音乐。
        final long musicStartAt = createdAt;
        // Boss 属于房间公共实体，由服务器保存状态，避免每个浏览器各自生成一只。
        volatile boolean bossAlive;
        volatile int bossGeneration;
        volatile int bossHp;
        volatile double bossX;
        volatile double bossZ;
        volatile long bossNextAt = createdAt + BOSS_SPAWN_DELAY_MS;
        volatile long bossNextMinionAt;

        FpsServerInfo(String serverId, String hostName, String password, int maxPlayers) {
            this.serverId = serverId;
            this.hostName = hostName;
            this.password = password == null ? "" : password;
            this.maxPlayers = maxPlayers;
        }
    }

    static class PendingHit {
        final String hitId;
        final String attacker;
        final String target;
        final int weaponIndex;
        final int attackerLifeGeneration;
        final int targetLifeGeneration;
        final long occurredAt;

        PendingHit(String hitId, String attacker, String target, int weaponIndex,
                int attackerLifeGeneration, int targetLifeGeneration, long occurredAt) {
            this.hitId = hitId;
            this.attacker = attacker;
            this.target = target;
            this.weaponIndex = weaponIndex;
            this.attackerLifeGeneration = attackerLifeGeneration;
            this.targetLifeGeneration = targetLifeGeneration;
            this.occurredAt = occurredAt;
        }
    }

    static class KillReward {
        final String attacker;
        final String victim;
        final int attackerLifeGeneration;
        final int weaponIndex;

        KillReward(String attacker, String victim, int attackerLifeGeneration, int weaponIndex) {
            this.attacker = attacker;
            this.victim = victim;
            this.attackerLifeGeneration = attackerLifeGeneration;
            this.weaponIndex = weaponIndex;
        }
    }

    static class BossHitResult {
        final String attacker;
        final int attackerLifeGeneration;
        final int weaponIndex;
        final boolean killed;

        BossHitResult(String attacker, int attackerLifeGeneration, int weaponIndex, boolean killed) {
            this.attacker = attacker;
            this.attackerLifeGeneration = attackerLifeGeneration;
            this.weaponIndex = weaponIndex;
            this.killed = killed;
        }
    }

    private static class PlayerState {
        final boolean alive;
        final int lifeGeneration;

        PlayerState(boolean alive, int lifeGeneration) {
            this.alive = alive;
            this.lifeGeneration = lifeGeneration;
        }
    }

    // 创建服务器；返回错误信息（null 表示成功）
    synchronized String create(String hostName, int maxPlayers, String password) {
        if (hostName == null || hostName.isEmpty()) {
            return "昵称无效";
        }
        if (hostToServer.containsKey(hostName) || memberToServer.containsKey(hostName)) {
            return "你已有一个多人游戏服务器，请先退出当前游戏";
        }
        if (maxPlayers < 2 || maxPlayers > 16) {
            return "人数需在 2~16 之间";
        }
        String cleanPass = password == null ? "" : password.trim();
        if (cleanPass.length() > 16) {
            return "密码过长（最多 16 位）";
        }
        String id = "fps" + System.currentTimeMillis() + "_" + sequence.incrementAndGet();
        FpsServerInfo info = new FpsServerInfo(id, hostName, cleanPass, maxPlayers);
        info.players.add(hostName);
        servers.put(id, info);
        hostToServer.put(hostName, id);
        memberToServer.put(hostName, id);
        server.log("多人游戏创建: " + hostName + " 人数上限 " + maxPlayers + (cleanPass.isEmpty() ? "（无密码）" : "（有密码）"));
        return null;
    }

    // 加入服务器；返回错误信息（null 表示成功）
    synchronized String join(String name, String serverId, String password) {
        FpsServerInfo info = servers.get(serverId);
        if (info == null) {
            return "服务器不存在或已关闭";
        }
        if (memberToServer.containsKey(name)) {
            return "你已在某个多人游戏中";
        }
        if (!info.password.isEmpty() && (password == null || !password.trim().equals(info.password))) {
            return "密码错误";
        }
        if (info.players.size() >= info.maxPlayers) {
            return "服务器人数已满";
        }
        info.players.add(name);
        memberToServer.put(name, serverId);
        server.log("多人游戏加入: " + name + " -> " + serverId + "（房主 " + info.hostName + "）");
        return null;
    }

    // 退出服务器；房主退出会删除服务器并通知其他成员
    // 返回: null=未在服务器中, "left"=成员离开, "closed"=房主离开导致服务器删除
    synchronized String leave(String name) {
        String serverId = memberToServer.remove(name);
        hostToServer.remove(name);
        if (serverId == null) {
            return null;
        }
        FpsServerInfo info = servers.get(serverId);
        if (info == null) {
            return null;
        }
        info.players.remove(name);
        info.playerStates.remove(name);
        info.deathLockedLifeGenerations.remove(name);
        removePendingHitsForPlayer(info, name);
        if (info.hostName.equals(name)) {
            servers.remove(serverId);
            for (String member : new ArrayList<>(info.players)) {
                memberToServer.remove(member);
                ClientHandler handler = server.userHandlers.get(member);
                if (handler != null) {
                    handler.sendMessage("/mp_server_closed|" + server.http.encodeWebValue(name));
                }
            }
            server.log("多人游戏关闭: 房主 " + name + " 退出，服务器 " + serverId + " 已删除");
            return "closed";
        }
        for (String member : info.players) {
            ClientHandler handler = server.userHandlers.get(member);
            if (handler != null) {
                handler.sendMessage("/mp_peer_left|" + server.http.encodeWebValue(name));
            }
        }
        return "left";
    }

    FpsServerInfo get(String serverId) {
        return servers.get(serverId);
    }

    String serverIdOf(String name) {
        return memberToServer.get(name);
    }

    boolean isHost(String name, String serverId) {
        String id = hostToServer.get(name);
        return id != null && id.equals(serverId);
    }

    void setBots(String hostName, boolean enabled) {
        String id = hostToServer.get(hostName);
        if (id == null) {
            return;
        }
        FpsServerInfo info = servers.get(id);
        if (info != null) {
            info.botsEnabled = enabled;
        }
    }

    List<FpsServerInfo> list() {
        List<FpsServerInfo> out = new ArrayList<>(servers.values());
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    // 向服务器内所有成员广播（exclude 为 null 时广播给所有人）
    void broadcast(String serverId, String message, String exclude) {
        FpsServerInfo info = servers.get(serverId);
        if (info == null) {
            return;
        }
        for (String member : info.players) {
            if (member.equals(exclude)) {
                continue;
            }
            ClientHandler handler = server.userHandlers.get(member);
            if (handler != null) {
                handler.sendMessage(message);
            }
        }
    }

    // 保存并转发玩家状态；状态快照用于后来加入的玩家立即看到已在场玩家。
    synchronized void updatePlayerState(String name, String data) {
        String serverId = memberToServer.get(name);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        PlayerState incoming = parsePlayerState(data);
        if (info == null || !info.players.contains(name) || data == null || data.length() >= 200 || incoming == null) {
            return;
        }
        PlayerState previous = parsePlayerState(info.playerStates.get(name));
        if (previous != null && incoming.lifeGeneration < previous.lifeGeneration) {
            // 网络延迟的旧状态不得回滚已经重生过的生命编号。
            return;
        }
        Integer lockedGeneration = info.deathLockedLifeGenerations.get(name);
        if (incoming.alive && lockedGeneration != null) {
            if (incoming.lifeGeneration <= lockedGeneration.intValue()) {
                // 同一条生命的旧存活包不能让已经死亡的玩家重新可受击或可攻击。
                return;
            }
            info.deathLockedLifeGenerations.remove(name, lockedGeneration);
            removePendingHitsForTarget(info, name);
        } else if (previous != null && incoming.lifeGeneration > previous.lifeGeneration) {
            // 即使先收到了新生命的死亡状态，旧生命的未确认命中也不再有效。
            removePendingHitsForTarget(info, name);
        }
        normalizePickups(info, System.currentTimeMillis());
        info.playerStates.put(name, data);
        broadcast(serverId, "/mp_peer|" + server.http.encodeWebValue(name) + "|" + data, name);
        // 每次状态心跳都校正一次房间公共道具，防止任一客户端保留本地旧可见状态。
        broadcastCurrentPickupState(serverId, info);
    }

    // 服务器权威判定：复活倒计时中的玩家不能造成伤害，也不能成为伤害目标。
    synchronized boolean canDamagePlayer(String attacker, String target) {
        String serverId = memberToServer.get(attacker);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || attacker.equals(target) || !info.players.contains(target)
                || !serverId.equals(memberToServer.get(target))) {
            return false;
        }
        return isPlayerAlive(info, attacker) && isPlayerAlive(info, target);
    }

    // 只接受与双方当前生命编号一致的命中，并分配服务端 hitId 供受害者确认死亡。
    synchronized PendingHit recordPlayerAttack(String attacker, String target, int weaponIndex,
            int attackerLifeGeneration, int targetLifeGeneration) {
        String serverId = memberToServer.get(attacker);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || attacker.equals(target) || !isValidWeaponIndex(weaponIndex)
                || !isValidLifeGeneration(attackerLifeGeneration) || !isValidLifeGeneration(targetLifeGeneration)
                || !info.players.contains(target)
                || !serverId.equals(memberToServer.get(target))) {
            return null;
        }
        if (!isPlayerAlive(info, attacker, attackerLifeGeneration)
                || !isPlayerAlive(info, target, targetLifeGeneration)) {
            return null;
        }
        long now = System.currentTimeMillis();
        pruneExpiredHits(info, now);
        if (info.pendingHits.size() >= MAX_PENDING_HITS) {
            return null;
        }
        String hitId = "h" + hitSequence.incrementAndGet();
        PendingHit hit = new PendingHit(hitId, attacker, target, weaponIndex,
                attackerLifeGeneration, targetLifeGeneration, now);
        info.pendingHits.put(hitId, hit);
        return hit;
    }

    // 受害者以最终致死命中的 hitId 确认死亡；同一生命编号只会进入一次结算。
    synchronized KillReward recordPlayerDeath(String victim, String hitId, int victimLifeGeneration) {
        String serverId = memberToServer.get(victim);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.players.contains(victim) || !isValidLifeGeneration(victimLifeGeneration)
                || hitId == null || hitId.isEmpty() || hitId.length() > 64) {
            return null;
        }
        long now = System.currentTimeMillis();
        pruneExpiredHits(info, now);
        Integer lockedGeneration = info.deathLockedLifeGenerations.get(victim);
        if (lockedGeneration != null && victimLifeGeneration <= lockedGeneration.intValue()) {
            return null;
        }
        PendingHit hit = info.pendingHits.get(hitId);
        if (hit == null || !victim.equals(hit.target) || hit.targetLifeGeneration != victimLifeGeneration
                || now - hit.occurredAt > KILL_CREDIT_WINDOW_MS) {
            return null;
        }
        PlayerState victimState = getPlayerState(info, victim);
        if (victimState == null || victimState.lifeGeneration != victimLifeGeneration || victimState.alive) {
            return null;
        }

        // 先锁住死亡，再检查奖励接收方；攻击者离开或重生也不能让同一死亡重复结算。
        info.pendingHits.remove(hitId, hit);
        info.deathLockedLifeGenerations.put(victim, victimLifeGeneration);
        removePendingHitsForTarget(info, victim);

        if (!info.players.contains(hit.attacker) || !serverId.equals(memberToServer.get(hit.attacker))
                || !isPlayerAtLifeGeneration(info, hit.attacker, hit.attackerLifeGeneration)) {
            return null;
        }
        return new KillReward(hit.attacker, victim, hit.attackerLifeGeneration, hit.weaponIndex);
    }

    private boolean isPlayerAlive(FpsServerInfo info, String name) {
        PlayerState state = getPlayerState(info, name);
        return state != null && state.alive && !isDeathLocked(info, name, state.lifeGeneration);
    }

    private boolean isPlayerAlive(FpsServerInfo info, String name, int lifeGeneration) {
        PlayerState state = getPlayerState(info, name);
        return state != null && state.alive && state.lifeGeneration == lifeGeneration
                && !isDeathLocked(info, name, lifeGeneration);
    }

    private boolean isPlayerAtLifeGeneration(FpsServerInfo info, String name, int lifeGeneration) {
        PlayerState state = getPlayerState(info, name);
        return state != null && state.lifeGeneration == lifeGeneration;
    }

    private boolean isDeathLocked(FpsServerInfo info, String name, int lifeGeneration) {
        Integer lockedGeneration = info.deathLockedLifeGenerations.get(name);
        return lockedGeneration != null && lifeGeneration <= lockedGeneration.intValue();
    }

    private PlayerState getPlayerState(FpsServerInfo info, String name) {
        return parsePlayerState(info.playerStates.get(name));
    }

    private static PlayerState parsePlayerState(String state) {
        if (state == null) {
            return null;
        }
        String[] fields = state.split("\\|", -1);
        if (fields.length < 11 || !("0".equals(fields[9]) || "1".equals(fields[9]))) {
            return null;
        }
        try {
            int lifeGeneration = Integer.parseInt(fields[10]);
            if (!isValidLifeGeneration(lifeGeneration)) {
                return null;
            }
            return new PlayerState("1".equals(fields[9]), lifeGeneration);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean isValidWeaponIndex(int weaponIndex) {
        return weaponIndex >= 0 && weaponIndex < WEAPON_COUNT;
    }

    static boolean isValidBulletDamage(int weaponIndex, int damage) {
        if (weaponIndex == 0) {
            return damage == 14 || damage == 50;
        }
        if (weaponIndex == 1) {
            return damage == 22 || damage == 70;
        }
        if (weaponIndex == 2) {
            return damage == 29 || damage == 92;
        }
        return weaponIndex == 3 && (damage == 17 || damage == 54);
    }

    static int playerKillReserveReward(int weaponIndex) {
        if (weaponIndex == 3) return 90;
        if (weaponIndex == 2) return 0;
        return 12;
    }

    static int playerKillHealthReward(int weaponIndex) {
        return weaponIndex == 3 ? 80 : 0;
    }

    static int botKillReserveReward(int weaponIndex) {
        if (weaponIndex == 2) return 27;
        if (weaponIndex == 3) return 160;
        return 0;
    }

    static boolean isValidBossDamage(int weaponIndex, int damage) {
        return damage == 75 || isValidBulletDamage(weaponIndex, damage);
    }

    static boolean isValidBotDamage(int weaponIndex, int damage) {
        return damage == 75 || isValidBulletDamage(weaponIndex, damage);
    }

    synchronized boolean forwardBotState(String hostName, String botId, int hp,
            double x, double z, double yaw, boolean alive) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.hostName.equals(hostName) || !isValidBotId(botId)
                || hp < 0 || hp > 100_000 || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(yaw)) {
            return false;
        }
        String state = "/mp_bot_state|" + server.http.encodeWebValue(botId) + "|" + hp + "|"
                + clampBossCoordinate(x) + "|" + clampBossCoordinate(z) + "|" + yaw + "|"
                + (alive ? "1" : "0");
        if (alive) info.botStates.put(botId, state);
        else info.botStates.remove(botId);
        broadcast(serverId, state, hostName);
        return true;
    }

    synchronized boolean forwardBotHit(String attacker, String botId, int damage,
            int weaponIndex, int attackerLifeGeneration) {
        String serverId = memberToServer.get(attacker);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || info.hostName.equals(attacker) || !isValidBotId(botId)
                || !isValidBotDamage(weaponIndex, damage)
                || !isValidLifeGeneration(attackerLifeGeneration)
                || !isPlayerAlive(info, attacker, attackerLifeGeneration)) {
            return false;
        }
        sendTo(info.hostName, "/mp_bot_hit_request|" + server.http.encodeWebValue(attacker) + "|"
                + server.http.encodeWebValue(botId) + "|" + damage + "|" + weaponIndex + "|"
                + attackerLifeGeneration);
        return true;
    }

    synchronized boolean confirmBotKill(String hostName, String botId, String attacker,
            int weaponIndex, int attackerLifeGeneration) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.hostName.equals(hostName) || !isValidBotId(botId)
                || !isValidWeaponIndex(weaponIndex) || !isValidLifeGeneration(attackerLifeGeneration)
                || !info.botStates.containsKey(botId)
                || attacker == null || !info.players.contains(attacker)
                || !serverId.equals(memberToServer.get(attacker))
                || !isPlayerAtLifeGeneration(info, attacker, attackerLifeGeneration)) {
            return false;
        }
        info.botStates.remove(botId);
        broadcast(serverId, "/mp_bot_remove|" + server.http.encodeWebValue(botId) + "|"
                + server.http.encodeWebValue(attacker) + "|" + weaponIndex + "|"
                + attackerLifeGeneration, null);
        return true;
    }

    synchronized boolean forwardBotAttack(String hostName, String target, int damage, String botId) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.hostName.equals(hostName) || damage != 10 || !isValidBotId(botId)
                || target == null || !info.players.contains(target) || !isPlayerAlive(info, target)) {
            return false;
        }
        sendTo(target, "/mp_bot_damage|" + damage + "|" + server.http.encodeWebValue(hostName)
                + "|bot-" + hitSequence.incrementAndGet());
        return true;
    }

    private static boolean isValidBotId(String botId) {
        if (botId == null || botId.isEmpty() || botId.length() > MAX_BOT_ID_LENGTH) return false;
        for (int i = 0; i < botId.length(); i++) {
            char c = botId.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean isValidLifeGeneration(int lifeGeneration) {
        return lifeGeneration >= 0 && lifeGeneration <= MAX_LIFE_GENERATION;
    }

    private void pruneExpiredHits(FpsServerInfo info, long now) {
        for (Map.Entry<String, PendingHit> entry : info.pendingHits.entrySet()) {
            PendingHit hit = entry.getValue();
            if (now - hit.occurredAt > KILL_CREDIT_WINDOW_MS) {
                info.pendingHits.remove(entry.getKey(), hit);
            }
        }
    }

    private void removePendingHitsForTarget(FpsServerInfo info, String target) {
        for (Map.Entry<String, PendingHit> entry : info.pendingHits.entrySet()) {
            PendingHit hit = entry.getValue();
            if (target.equals(hit.target)) {
                info.pendingHits.remove(entry.getKey(), hit);
            }
        }
    }

    private void removePendingHitsForPlayer(FpsServerInfo info, String player) {
        for (Map.Entry<String, PendingHit> entry : info.pendingHits.entrySet()) {
            PendingHit hit = entry.getValue();
            if (player.equals(hit.attacker) || player.equals(hit.target)) {
                info.pendingHits.remove(entry.getKey(), hit);
            }
        }
    }

    // 将房间地图、共享道具和已有玩家快照发送给新加入的成员。
    synchronized void sendRoomSnapshot(String serverId, String member) {
        FpsServerInfo info = servers.get(serverId);
        if (info == null || !info.players.contains(member)) {
            return;
        }
        int respawned = normalizePickups(info, System.currentTimeMillis());
        sendTo(member, "/mp_world|" + SHARED_MAP_ID + "|"
                + SHARED_BLOOD_POSITION + "|" + SHARED_AMMO_POSITION + "|"
                + (info.bloodVisible ? "1" : "0") + "|" + info.bloodRespawnAt + "|"
                + (info.ammoVisible ? "1" : "0") + "|" + info.ammoRespawnAt);
        for (Map.Entry<String, String> entry : info.playerStates.entrySet()) {
            if (!entry.getKey().equals(member)) {
                sendTo(member, "/mp_peer|" + server.http.encodeWebValue(entry.getKey()) + "|" + entry.getValue());
            }
        }
        for (String botState : info.botStates.values()) {
            sendTo(member, botState);
        }
        sendBossState(info, member, System.currentTimeMillis());
        if (respawned != 0) {
            broadcastCurrentPickupState(serverId, info);
        }
    }

    // 房主请求生成 Boss；生成时间由服务器判断，位置只接受地图范围内的有限数值。
    synchronized boolean requestBossSpawn(String hostName, double x, double z) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        long now = System.currentTimeMillis();
        if (info == null || !info.hostName.equals(hostName) || info.bossAlive || now < info.bossNextAt) {
            return false;
        }
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            x = ThreadLocalRandom.current().nextDouble(-24.0, 24.0);
            z = ThreadLocalRandom.current().nextDouble(-24.0, 24.0);
        }
        info.bossGeneration = info.bossGeneration >= Integer.MAX_VALUE ? 1 : info.bossGeneration + 1;
        info.bossAlive = true;
        info.bossHp = BOSS_HP;
        info.bossX = clampBossCoordinate(x);
        info.bossZ = clampBossCoordinate(z);
        info.bossNextMinionAt = now + BOSS_MINION_DELAY_MS;
        broadcastBossState(info, now);
        return true;
    }

    // 房主同步 Boss 移动位置；生命值和计时仍由服务器维护。
    synchronized boolean updateBossPosition(String hostName, int generation, double x, double z) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.hostName.equals(hostName) || !info.bossAlive
                || info.bossGeneration != generation || !Double.isFinite(x) || !Double.isFinite(z)) {
            return false;
        }
        info.bossX = clampBossCoordinate(x);
        info.bossZ = clampBossCoordinate(z);
        broadcastBossState(info, System.currentTimeMillis());
        return true;
    }

    // 房主请求 Boss 波次；服务器保证每 10 秒最多一波，并把同一中心点广播给所有成员。
    synchronized boolean requestBossWave(String hostName, int generation, double x, double z) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        long now = System.currentTimeMillis();
        if (info == null || !info.hostName.equals(hostName) || !info.bossAlive
                || info.bossGeneration != generation || now < info.bossNextMinionAt) {
            return false;
        }
        info.bossX = clampBossCoordinate(Double.isFinite(x) ? x : info.bossX);
        info.bossZ = clampBossCoordinate(Double.isFinite(z) ? z : info.bossZ);
        info.bossNextMinionAt = now + BOSS_MINION_DELAY_MS;
        broadcast(info.serverId, "/mp_boss_wave|" + info.bossGeneration + "|"
                + info.bossX + "|" + info.bossZ + "|" + now, null);
        broadcastBossState(info, now);
        return true;
    }

    // 玩家命中 Boss；服务器统一扣血，击杀后从死亡时刻重新计算 90 秒计时。
    synchronized BossHitResult recordBossHit(String attacker, int generation, int damage,
            int weaponIndex, int attackerLifeGeneration) {
        String serverId = memberToServer.get(attacker);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.bossAlive || info.bossGeneration != generation
                || !isValidWeaponIndex(weaponIndex) || !isValidBossDamage(weaponIndex, damage)
                || !isValidLifeGeneration(attackerLifeGeneration)
                || !isPlayerAlive(info, attacker, attackerLifeGeneration)) {
            return null;
        }
        info.bossHp = Math.max(0, info.bossHp - damage);
        boolean killed = info.bossHp <= 0;
        long now = System.currentTimeMillis();
        if (killed) {
            info.bossAlive = false;
            info.bossNextMinionAt = 0;
            info.bossNextAt = now + BOSS_SPAWN_DELAY_MS;
            broadcast(info.serverId, "/mp_boss_kill|" + info.bossGeneration + "|"
                    + server.http.encodeWebValue(attacker) + "|" + weaponIndex, null);
        }
        broadcastBossState(info, now);
        return new BossHitResult(attacker, attackerLifeGeneration, weaponIndex, killed);
    }

    // 房主负责 Boss 的近战攻击判定；服务器只校验房主身份、代数和目标生命状态，再转发伤害。
    synchronized boolean forwardBossAttack(String hostName, String target, int generation, int damage) {
        String serverId = hostToServer.get(hostName);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !info.hostName.equals(hostName) || !info.bossAlive
                || info.bossGeneration != generation || damage != 75 || target == null
                || target.isEmpty() || target.length() > 64 || !info.players.contains(target)
                || !isPlayerAlive(info, target)) {
            return false;
        }
        sendTo(target, "/mp_boss_damage|75|" + server.http.encodeWebValue(hostName));
        return true;
    }

    private void sendBossState(FpsServerInfo info, String member, long now) {
        sendTo(member, bossStateMessage(info, now));
    }

    private void broadcastBossState(FpsServerInfo info, long now) {
        broadcast(info.serverId, bossStateMessage(info, now), null);
    }

    private String bossStateMessage(FpsServerInfo info, long now) {
        return "/mp_boss_state|" + info.bossGeneration + "|" + (info.bossAlive ? "1" : "0")
                + "|" + info.bossHp + "|" + info.bossX + "|" + info.bossZ + "|"
                + info.bossNextAt + "|" + info.bossNextMinionAt + "|" + now;
    }

    private static double clampBossCoordinate(double value) {
        return Math.max(-27.5, Math.min(27.5, value));
    }

    // 尝试拾取房间道具；返回要广播的状态，不成功时返回 null。
    synchronized String claimPickup(String name, String type) {
        String serverId = memberToServer.get(name);
        FpsServerInfo info = serverId == null ? null : servers.get(serverId);
        if (info == null || !("blood".equals(type) || "ammo".equals(type))) {
            return null;
        }
        long now = System.currentTimeMillis();
        normalizePickups(info, now);
        if ("blood".equals(type)) {
            if (!info.bloodVisible) {
                return null;
            }
            info.bloodVisible = false;
            info.bloodRespawnAt = now + PICKUP_RESPAWN_MS;
            return serverId + "|blood|0|" + info.bloodRespawnAt;
        }
        if (!info.ammoVisible) {
            return null;
        }
        info.ammoVisible = false;
        info.ammoRespawnAt = now + PICKUP_RESPAWN_MS;
        return serverId + "|ammo|0|" + info.ammoRespawnAt;
    }

    private int normalizePickups(FpsServerInfo info, long now) {
        int respawned = 0;
        if (!info.bloodVisible && info.bloodRespawnAt > 0 && now >= info.bloodRespawnAt) {
            info.bloodVisible = true;
            info.bloodRespawnAt = 0;
            respawned |= 1;
        }
        if (!info.ammoVisible && info.ammoRespawnAt > 0 && now >= info.ammoRespawnAt) {
            info.ammoVisible = true;
            info.ammoRespawnAt = 0;
            respawned |= 2;
        }
        return respawned;
    }

    private void broadcastCurrentPickupState(String serverId, FpsServerInfo info) {
        broadcast(serverId, "/mp_pickup_state|blood|" + (info.bloodVisible ? "1" : "0")
                + "|" + info.bloodRespawnAt + "|c2VydmVy", null);
        broadcast(serverId, "/mp_pickup_state|ammo|" + (info.ammoVisible ? "1" : "0")
                + "|" + info.ammoRespawnAt + "|c2VydmVy", null);
    }

    void sendTo(String member, String message) {
        ClientHandler handler = server.userHandlers.get(member);
        if (handler != null) {
            handler.sendMessage(message);
        }
    }
}

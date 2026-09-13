    "use strict";

    const DEFAULT_CLIENT_VERSION = "3.0.2";
    const IMAGE_CHUNK_SIZE = 50000;
    const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    const IDLE_LIMIT_MS = 60 * 60 * 1000;
    const LIVE_SAMPLE_RATE = 8000;
    const LIVE_FRAME_SAMPLES = 160;
    const LIVE_FRAME_BYTES = 320;

    const $ = (id) => document.getElementById(id);
    const ui = {
      gateScreen: $("gateScreen"), loginScreen: $("loginScreen"), appScreen: $("appScreen"),
      gamesScreen: $("gamesScreen"), snakeScreen: $("snakeScreen"), clickScreen: $("clickScreen"),
      earthScreen: $("earthScreen"), earthStage: $("earthStage"), earthStageBack: $("earthStageBack"), earthModeTip: $("earthModeTip"),
      earthInfo: $("earthInfo"), earthBackButton: $("earthBackButton"),
      earthModeNone: $("earthModeNone"), earthModeRain: $("earthModeRain"),
      earthModeSnow: $("earthModeSnow"), earthModeMeteor: $("earthModeMeteor"), earthModeConflict: $("earthModeConflict"),
      earthZoom: $("earthZoom"), earthConflicts: $("earthConflicts"),
      fpsScreen: $("fpsScreen"), fpsStage: $("fpsStage"), fpsMenu: $("fpsMenu"), fpsHud: $("fpsHud"),
      fpsSettle: $("fpsSettle"), fpsBestScore: $("fpsBestScore"), fpsStartButton: $("fpsStartButton"),
      fpsBackButton: $("fpsBackButton"), fpsHp: $("fpsHp"), fpsAmmo: $("fpsAmmo"), fpsReserve: $("fpsReserve"),
      fpsScore: $("fpsScore"), fpsScoreWrap: $("fpsScoreWrap"), fpsDamageFlash: $("fpsDamageFlash"), fpsSettleScore: $("fpsSettleScore"),
      fpsSettleBest: $("fpsSettleBest"), fpsSettleRank: $("fpsSettleRank"), fpsSettleBackButton: $("fpsSettleBackButton"),
      fpsPause: $("fpsPause"), fpsResumeButton: $("fpsResumeButton"), fpsPauseExitButton: $("fpsPauseExitButton"),
      fpsMpButton: $("fpsMpButton"), fpsMpPanel: $("fpsMpPanel"), fpsMpMax: $("fpsMpMax"), fpsMpCreatePass: $("fpsMpCreatePass"),
      fpsMpCreateButton: $("fpsMpCreateButton"), fpsMpRefreshButton: $("fpsMpRefreshButton"), fpsMpServers: $("fpsMpServers"),
      fpsMpBackButton: $("fpsMpBackButton"), fpsMpInfo: $("fpsMpInfo"), fpsWeaponInfo: $("fpsWeaponInfo"),
      fpsSelfId: $("fpsSelfId"), fpsRespawnOverlay: $("fpsRespawnOverlay"),
      fpsTouchControls: $("fpsTouchControls"), tBtnW: $("tBtnW"), tBtnA: $("tBtnA"), tBtnS: $("tBtnS"),
      tBtnD: $("tBtnD"), tBtnSpace: $("tBtnSpace"), tBtnFire: $("tBtnFire"), tBtnF: $("tBtnF"),
      tBtnZ: $("tBtnZ"), tBtnG: $("tBtnG"), tBtnX: $("tBtnX"), tBtnJ: $("tBtnJ"), tBtnExit: $("tBtnExit"),
      gamesButton: $("gamesButton"), gamesCount: $("gamesCount"), gameList: $("gameList"),
      gamesBackButton: $("gamesBackButton"),
      webPanScreen: $("webPanScreen"), webPanFrame: $("webPanFrame"),
      gateDot: $("gateDot"), gateConnectionText: $("gateConnectionText"), challengeQuestion: $("challengeQuestion"),
      verifyForm: $("verifyForm"), verifyAnswer: $("verifyAnswer"), verifyButton: $("verifyButton"), verifyError: $("verifyError"),
      reconnectButton: $("reconnectButton"), loginForm: $("loginForm"), nicknameInput: $("nicknameInput"),
      channelSelect: $("channelSelect"), channelPasswordField: $("channelPasswordField"),
      channelPasswordInput: $("channelPasswordInput"), loginButton: $("loginButton"), loginError: $("loginError"),
      topChannelName: $("topChannelName"), topNickname: $("topNickname"), myP2PCode: $("myP2PCode"),
      disconnectButton: $("disconnectButton"), workspace: $("workspace"), currentChannelName: $("currentChannelName"),
      onlineUsers: $("onlineUsers"), onlineCount: $("onlineCount"), historyState: $("historyState"),
      messageFeed: $("messageFeed"), emptyMessages: $("emptyMessages"), channelMessageForm: $("channelMessageForm"),
      channelMessageInput: $("channelMessageInput"), imageButton: $("imageButton"), imageInput: $("imageInput"),
      voiceNoteButton: $("voiceNoteButton"), composerStatus: $("composerStatus"), groupVoiceIndicator: $("groupVoiceIndicator"),
      groupVoiceStatus: $("groupVoiceStatus"), groupVoiceButton: $("groupVoiceButton"), selectedPeer: $("selectedPeer"),
      peerPasswordInput: $("peerPasswordInput"), p2pConnectButton: $("p2pConnectButton"), p2pConnectForm: $("p2pConnectForm"),
      deepSeekForm: $("deepSeekForm"), deepSeekInput: $("deepSeekInput"), privateDialog: $("privateDialog"),
      privatePeerName: $("privatePeerName"), privateStatus: $("privateStatus"), privateFeed: $("privateFeed"),
      privateMessageForm: $("privateMessageForm"), privateMessageInput: $("privateMessageInput"),
      privateCallButton: $("privateCallButton"), privateHangupButton: $("privateHangupButton"),
      privateCloseButton: $("privateCloseButton"), incomingCallDialog: $("incomingCallDialog"),
      incomingCallText: $("incomingCallText"), acceptCallButton: $("acceptCallButton"), rejectCallButton: $("rejectCallButton"),
      snakeTitle: $("snakeTitle"), snakeScore: $("snakeScore"), snakeStatus: $("snakeStatus"),
      snakeCanvas: $("snakeCanvas"), snakeOverlay: $("snakeOverlay"), snakeOverlayTitle: $("snakeOverlayTitle"),
      snakeOverlayScore: $("snakeOverlayScore"), snakeRestartButton: $("snakeRestartButton"), snakeBackButton: $("snakeBackButton"),
      snakeRankList: $("snakeRankList"), clickTitle: $("clickTitle"),
      clickScore: $("clickScore"), clickStatus: $("clickStatus"), clickCanvas: $("clickCanvas"),
      clickOverlay: $("clickOverlay"), clickOverlayTitle: $("clickOverlayTitle"), clickOverlayScore: $("clickOverlayScore"),
      clickStartButton: $("clickStartButton"), clickRestartButton: $("clickRestartButton"), clickBackButton: $("clickBackButton"), clickRankList: $("clickRankList"),
      toastRegion: $("toastRegion")
    };

    const state = {
      socket: null,
      clientVersion: DEFAULT_CLIENT_VERSION,
      manuallyDisconnected: false,
      verified: false,
      webPanPending: new URLSearchParams(location.search).get("webpan") === "1",
      channels: [],
      channelsComplete: false,
      loginPhase: "idle",
      selectedChannel: null,
      nickname: "",
      sessionReady: false,
      historyReceiving: false,
      historyReceived: false,
      onlineUsers: [],
      selectedPeerName: "",
      myP2PPassword: "-----",
      pendingP2PVerify: null,
      activePrivatePeer: "",
      conversations: new Map(),
      incomingCall: null,
      outgoingCallPeer: "",
      liveMode: null,
      livePeer: "",
      liveStarting: false,
      liveStartToken: null,
      live: null,
      note: null,
      heartbeatTimer: null,
      idleTimer: null,
      lastMeaningfulActivity: Date.now(),
      imageReceivers: new Map(),
      objectUrls: new Set()
    };

    function showScreen(name) {
      if (name !== "webpan" && !ui.webPanScreen.hidden) ui.webPanFrame.src = "about:blank";
      ui.webPanScreen.hidden = name !== "webpan";
      ui.gateScreen.hidden = name !== "gate";
      ui.loginScreen.hidden = name !== "login";
      ui.appScreen.hidden = name !== "app";
      ui.gamesScreen.hidden = name !== "games";
      ui.snakeScreen.hidden = name !== "snake";
      ui.clickScreen.hidden = name !== "click";
      ui.earthScreen.hidden = name !== "earth";
      ui.fpsScreen.hidden = name !== "fps";
    }

    function setGateStatus(text, mode) {
      ui.gateConnectionText.textContent = text;
      ui.gateDot.className = "status-dot" + (mode ? " " + mode : "");
    }

    function enterWebPan() {
      if (!state.verified || !isOpen()) {
        state.webPanPending = true;
        showScreen("gate");
        return;
      }
      state.webPanPending = false;
      showScreen("webpan");
      ui.webPanFrame.src = "/webpan/";
    }

    function showToast(message, type = "info", duration = 4200) {
      const item = document.createElement("div");
      item.className = "toast " + type;
      item.textContent = message;
      ui.toastRegion.appendChild(item);
      while (ui.toastRegion.children.length > 2) ui.toastRegion.firstElementChild.remove();
      window.setTimeout(() => item.remove(), duration);
    }

    function encodeBase64Utf8(value) {
      const bytes = new TextEncoder().encode(value);
      let binary = "";
      for (let i = 0; i < bytes.length; i += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      }
      return btoa(binary);
    }

    function decodeBase64Utf8(value) {
      try {
        const binary = atob(value);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
      } catch (_) {
        return "";
      }
    }

    function bytesToBase64(bytes) {
      let binary = "";
      for (let i = 0; i < bytes.length; i += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      }
      return btoa(binary);
    }

    function base64ToBytes(value) {
      try {
        const binary = atob(value);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
      } catch (_) {
        return null;
      }
    }

    function protocolSafe(value) {
      return !/[|\r\n\u0000-\u001f]/.test(value);
    }

    function isOpen() {
      return state.socket && state.socket.readyState === WebSocket.OPEN;
    }

    function sendProtocol(message, meaningful = false) {
      if (!isOpen()) return false;
      state.socket.send(message);
      if (meaningful) state.lastMeaningfulActivity = Date.now();
      return true;
    }

    function websocketUrl() {
      const secure = location.protocol === "https:";
      return (secure ? "wss://" : "ws://") + location.host + "/ws";
    }

    function resetConnectionState() {
      state.clientVersion = DEFAULT_CLIENT_VERSION;
      state.verified = false;
      state.channels = [];
      state.channelsComplete = false;
      state.loginPhase = "idle";
      state.selectedChannel = null;
      state.sessionReady = false;
      state.historyReceiving = false;
      state.historyReceived = false;
      state.onlineUsers = [];
      state.selectedPeerName = "";
      state.pendingP2PVerify = null;
      state.activePrivatePeer = "";
      state.conversations.clear();
      state.incomingCall = null;
      state.outgoingCallPeer = "";
      state.myP2PPassword = "-----";
      state.imageReceivers.clear();
      state.objectUrls.forEach((url) => URL.revokeObjectURL(url));
      state.objectUrls.clear();
      ui.myP2PCode.textContent = "-----";
      ui.emptyMessages.hidden = false;
      ui.messageFeed.replaceChildren(ui.emptyMessages);
      ui.historyState.textContent = "正在同步";
      ui.nicknameInput.disabled = false;
      ui.channelPasswordInput.disabled = false;
      ui.privateFeed.replaceChildren();
      setGroupVoiceUi("idle", "未加入");
      stopAllGames();
      renderChannels();
      renderOnlineUsers();
    }

    function connectWebSocket() {
      cleanupSocketTimers();
      cleanupLiveAudio();
      stopVoiceNote(false);
      resetConnectionState();
      state.manuallyDisconnected = false;
      showScreen("gate");
      ui.verifyForm.hidden = false;
      ui.reconnectButton.hidden = true;
      ui.verifyAnswer.value = "";
      ui.verifyAnswer.disabled = true;
      ui.verifyButton.disabled = true;
      ui.verifyError.textContent = "";
      ui.challengeQuestion.textContent = "正在获取验证问题...";
      setGateStatus("正在连接服务器", "busy");

      if (!location.host) {
        handleSocketClosed("该页面需要由聊天服务器打开");
        return;
      }

      try {
        state.socket = new WebSocket(websocketUrl());
      } catch (_) {
        handleSocketClosed("无法创建服务器连接");
        return;
      }

      state.socket.addEventListener("open", () => {
        setGateStatus("已连接，等待验证", "online");
        state.heartbeatTimer = window.setInterval(() => sendProtocol("/ping"), 30000);
      });
      state.socket.addEventListener("message", (event) => {
        if (typeof event.data === "string") handleServerMessage(event.data);
      });
      state.socket.addEventListener("error", () => {
        if (!state.manuallyDisconnected) setGateStatus("连接出现错误", "");
      });
      state.socket.addEventListener("close", () => {
        const message = state.manuallyDisconnected ? "连接已断开" : "服务器连接已关闭";
        handleSocketClosed(message);
      });
    }

    function cleanupSocketTimers() {
      if (state.heartbeatTimer) window.clearInterval(state.heartbeatTimer);
      if (state.idleTimer) window.clearInterval(state.idleTimer);
      state.heartbeatTimer = null;
      state.idleTimer = null;
    }

    function handleSocketClosed(message) {
      cleanupSocketTimers();
      cleanupLiveAudio();
      stopVoiceNote(false);
      stopAllGames(); // 服务器断开时同步取消本页所有小游戏进程
      state.sessionReady = false;
      if (ui.privateDialog.open) ui.privateDialog.close();
      if (ui.incomingCallDialog.open) ui.incomingCallDialog.close();
      showScreen("gate");
      ui.verifyForm.hidden = true;
      ui.reconnectButton.hidden = false;
      ui.challengeQuestion.textContent = message;
      setGateStatus("未连接", "");
    }

    function disconnect(reason) {
      state.manuallyDisconnected = true;
      if (isOpen()) {
        try { state.socket.send("/web_disconnect"); } catch (_) { }
        state.socket.close(1000, "client disconnect");
      } else {
        handleSocketClosed(reason || "连接已断开");
      }
      if (reason) showToast(reason, "info");
    }

    function handleServerMessage(message) {
      if (message.startsWith("/web_challenge|")) {
        const question = decodeBase64Utf8(message.substring(15));
        ui.challengeQuestion.textContent = question || "运营者是谁？";
        ui.verifyAnswer.disabled = false;
        ui.verifyButton.disabled = false;
        ui.verifyAnswer.focus();
        return;
      }
      if (message.startsWith("/web_verify_result|")) {
        const result = message.substring(19).split("|", 1)[0];
        if (result === "success") {
          state.verified = true;
          ui.verifyError.textContent = "";
          if (state.webPanPending) enterWebPan();
          else {
            showScreen("login");
            ui.nicknameInput.focus();
          }
        } else {
          ui.verifyError.textContent = "回答不正确，请重新输入";
          ui.verifyAnswer.select();
          ui.verifyButton.disabled = false;
        }
        return;
      }
      if (message.startsWith("/web_minimum_version|")) {
        const version = message.substring("/web_minimum_version|".length).trim();
        if (/^\d+(?:\.\d+){0,3}$/.test(version)) state.clientVersion = version;
        return;
      }
      if (message.startsWith("/web_login_error|")) {
        failLogin(message.substring(17) || "频道登录失败");
        return;
      }
      if (message.startsWith("/web_error|")) {
        showToast(message.substring(11) || "网页请求失败", "error");
        return;
      }
      if (message.startsWith("/web_idle_timeout|") || message.startsWith("/web_shutdown|") || message.startsWith("/server_shutdown|")) {
        const separator = message.indexOf("|");
        const reason = separator >= 0 ? message.substring(separator + 1) : "服务器已断开连接";
        state.manuallyDisconnected = true;
        handleSocketClosed(reason);
        if (isOpen()) state.socket.close(1000, "server shutdown");
        return;
      }
      if (message.startsWith("/web_channel|")) {
        const parts = message.split("|", 4);
        if (parts.length === 4) {
          const channel = {
            name: decodeBase64Utf8(parts[1]),
            group: decodeBase64Utf8(parts[2]),
            requiresPassword: parts[3] === "1"
          };
          if (channel.name && channel.group) {
            const index = state.channels.findIndex((item) => item.group === channel.group);
            if (index >= 0) state.channels[index] = channel;
            else state.channels.push(channel);
            renderChannels();
          }
        }
        return;
      }
      if (message === "/web_channels_end") {
        state.channelsComplete = true;
        renderChannels();
        return;
      }
      if (message.startsWith("/version_check|")) {
        const result = message.substring(15);
        if (result === "success") continueLoginAfterVersion();
        else failLogin("客户端版本不符合服务器要求");
        return;
      }
      if (message.startsWith("/login_result|")) {
        const result = message.substring(14);
        if (result === "success") sendSessionIdentity();
        else failLogin("频道名称或密码不正确");
        return;
      }
      if (message === "/session_ready|success") {
        enterChat();
        return;
      }
      if (message.startsWith("/history|")) {
        handleHistory(message.substring(9));
        return;
      }
      if (message.startsWith("/online_users|")) {
        state.onlineUsers = message.substring(14).split(",").map((value) => value.trim()).filter(Boolean);
        renderOnlineUsers();
        return;
      }
      if (message.startsWith("/p2p_password|")) {
        state.myP2PPassword = message.substring(14);
        ui.myP2PCode.textContent = state.myP2PPassword || "-----";
        return;
      }
      if (message.startsWith("/p2p_notification|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3) {
          ensureConversation(parts[1], parts[2]);
          appendPrivateMessage(parts[1], "系统", "对方已向你发起私聊", false, true);
          openPrivateChat(parts[1]);
          showToast(parts[1] + " 已发起私聊", "info");
        }
        return;
      }
      if (message.startsWith("/p2p_verify_result|")) {
        handleP2PVerifyResult(message);
        return;
      }
      if (message.startsWith("/p2p_msg|")) {
        const parts = message.split("|", 4);
        if (parts.length === 4) {
          ensureConversation(parts[1], parts[2]);
          appendPrivateMessage(parts[1], parts[1], parts[3], false, false);
          if (!ui.privateDialog.open || state.activePrivatePeer !== parts[1]) showToast(parts[1] + " 发来一条私聊消息", "info");
        }
        return;
      }
      if (message.startsWith("/p2p_error|")) {
        showToast(message.substring(11), "error");
        return;
      }
      if (message.startsWith("/live_group_joined|")) {
        const parts = message.split("|", 3);
        setGroupVoiceUi("active", (parts[2] || "1") + " 人");
        startLiveAudio("GROUP", "");
        return;
      }
      if (message === "/live_group_left") {
        cleanupLiveAudio();
        setGroupVoiceUi("idle", "未加入");
        return;
      }
      if (message.startsWith("/live_group_disabled|")) {
        cleanupLiveAudio();
        setGroupVoiceUi("disabled", "已被服务器关闭");
        return;
      }
      if (message.startsWith("/live_group_members|")) {
        if (state.liveMode === "GROUP") setGroupVoiceUi("active", message.substring(20) + " 人");
        return;
      }
      if (message.startsWith("/live_group_audio|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3 && state.liveMode === "GROUP") enqueueLiveFrame(parts[2]);
        return;
      }
      if (message.startsWith("/live_p2p_request|")) {
        handleIncomingCall(message);
        return;
      }
      if (message.startsWith("/live_p2p_request_sent|")) {
        state.outgoingCallPeer = message.substring(23);
        updatePrivateCallUi("等待对方接听");
        return;
      }
      if (message.startsWith("/live_p2p_started|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3) {
          ensureConversation(parts[1], parts[2]);
          state.outgoingCallPeer = "";
          state.livePeer = parts[1];
          openPrivateChat(parts[1]);
          startLiveAudio("P2P", parts[1]);
        }
        return;
      }
      if (message.startsWith("/live_p2p_audio|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3 && state.liveMode === "P2P" && parts[1] === state.livePeer) enqueueLiveFrame(parts[2]);
        return;
      }
      if (message.startsWith("/live_p2p_ended|")) {
        const peer = message.substring(16);
        cleanupLiveAudio();
        state.outgoingCallPeer = "";
        updatePrivateCallUi(peer + " 已结束语音通话");
        return;
      }
      if (message.startsWith("/live_p2p_rejected|") || message.startsWith("/live_p2p_rejected_ack|") || message.startsWith("/live_p2p_cancelled|")) {
        const parts = message.split("|", 3);
        state.outgoingCallPeer = "";
        state.liveStarting = false;
        const reason = parts[2] || (message.startsWith("/live_p2p_rejected_ack|") ? "已拒绝语音申请" : "语音申请已取消");
        updatePrivateCallUi(reason);
        showToast(reason, "info");
        return;
      }
      if (message.startsWith("/live_voice_error|")) {
        state.liveStarting = false;
        state.outgoingCallPeer = "";
        if (!state.liveMode) setGroupVoiceUi("idle", "未加入");
        updatePrivateCallUi("语音未连接");
        showToast(message.substring(18), "error");
        return;
      }
      if (message.startsWith("/voice_with_sender|")) {
        const parts = message.split("|", 4);
        if (parts.length === 4) addVoiceMessage(parts[1], parts[2], parts[3], parts[1] === state.nickname);
        return;
      }
      if (message.startsWith("/voice|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3) addVoiceMessage("其他用户", parts[1], parts[2], false);
        return;
      }
      if (message.startsWith("/image_info|")) {
        handleImageInfo(message);
        return;
      }
      if (message.startsWith("/image_chunk|")) {
        handleImageChunk(message);
        return;
      }
      if (message.startsWith("/deepseek_answer|")) {
        appendMessage("DeepSeek", message.substring(17), false, "ai");
        return;
      }
      if (message.startsWith("/game_record_saved|")) {
        // 战绩已由服务器保存，无需额外处理
        return;
      }
      if (message.startsWith("/game_rank_result|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3) {
          const gamesName = decodeBase64Utf8(parts[1]);
          const payload = decodeBase64Utf8(parts[2]);
          const rows = payload.split("\n").filter(Boolean).map((line) => {
            const fields = line.split("\t");
            return { rank: Number(fields[0]), id: fields[1] || "", score: Number(fields[2]) };
          });
          if (gamesName === "贪吃蛇") renderRankList(ui.snakeRankList, rows, state.nickname);
          else if (gamesName === "点格子") renderRankList(ui.clickRankList, rows, state.nickname);
          else if (gamesName === "3D射击生存") {
            const mine = rows.find((r) => r.id === state.nickname);
            FPS_STATE.rank = mine ? mine.rank : null;
            if (!ui.fpsSettle.hidden) {
              ui.fpsSettleRank.textContent = FPS_STATE.rank != null ? "第 " + FPS_STATE.rank + " 名" : "未上榜（前 100）";
            }
          }
        }
        return;
      }
      if (message.startsWith("/mp_list_result|")) {
        const rows = decodeBase64Utf8(message.substring(16)).split("\n").filter(Boolean).map((line) => {
          const f = line.split("|");
          return { id: f[0], host: f[1], max: f[2], current: f[3], pass: f[4], bots: f[5] };
        });
        renderMpServers(rows);
        return;
      }
      if (message.startsWith("/mp_create_result|")) {
        const parts = message.split("|");
        ui.fpsMpCreateButton.disabled = false;
        if (parts[1] === "ok") {
          showToast("多人服务器已创建，正在进入...", "success", 2000);
          mpEnterGame("host", parts[2], state.nickname, Number(parts[3]) || 0, Number(parts[4]) || 0);
        } else {
          fpsStopMpMusic();
          showToast(parts[2] || "创建失败", "error", 3000);
        }
        return;
      }
      if (message.startsWith("/mp_join_result|")) {
        const parts = message.split("|");
        if (parts[1] === "ok") {
          showToast("已加入多人服务器", "success", 2000);
          mpEnterGame("member", parts[2], parts[4] || "", Number(parts[5]) || 0, Number(parts[6]) || 0);
          // 服务器当前机器人状态（房主已关闭则加入后也不刷新）
          FPS_STATE.mp.botsEnabled = parts[3] === "1";
        } else {
          fpsStopMpMusic();
          showToast(parts[2] || "加入失败", "error", 3000);
          mpRefreshList();
        }
        return;
      }
      if (message.startsWith("/mp_world|")) {
        // 多人游戏的同一张地图：房间只使用一个共享地图标识，道具状态也来自服务器。
        const parts = message.split("|");
        if (parts.length === 12 && FPS_STATE.mp.mode !== "solo") {
          applyMpWorldState(parts[1],
            Number(parts[2]), Number(parts[3]), Number(parts[4]),
            Number(parts[5]), Number(parts[6]), Number(parts[7]),
            parts[8] === "1", Number(parts[9]) || 0,
            parts[10] === "1", Number(parts[11]) || 0);
        }
        return;
      }
      if (message.startsWith("/mp_peer|")) {
        // JS 的 split(limit) 会截断后续字段，玩家状态必须保留全部坐标/生命字段。
        const parts = message.split("|");
        if (parts.length >= 3 && FPS_STATE.mp.mode !== "solo") {
          upsertPeer(decodeBase64Utf8(parts[1]), parts.slice(2));
        }
        return;
      }
      if (message.startsWith("/mp_pickup_state|")) {
        const parts = message.split("|", 5);
        if (parts.length === 5 && FPS_STATE.mp.mode !== "solo") {
          applyMpPickupState(parts[1], parts[2] === "1", Number(parts[3]) || 0, decodeBase64Utf8(parts[4]));
        }
        return;
      }
      if (message.startsWith("/mp_sync_request|")) {
        // 服务器请求在场玩家补发一次完整快照，触屏端也能可靠建立远程模型。
        if (FPS_STATE.mp.mode !== "solo") mpBroadcastState();
        return;
      }
      if (message.startsWith("/mp_peer_left|")) {
        if (FPS_STATE.mp.mode !== "solo") {
          removePeer(decodeBase64Utf8(message.substring(14)));
          showToast(decodeBase64Utf8(message.substring(14)) + " 离开了游戏", "info", 1800);
        }
        return;
      }
      if (message.startsWith("/mp_peer_joined|")) {
        if (FPS_STATE.mp.mode !== "solo") {
          showToast(decodeBase64Utf8(message.substring(16)) + " 加入了游戏", "info", 1800);
          mpBroadcastState(); // 立即同步自己的状态给新玩家
        }
        return;
      }
      if (message.startsWith("/mp_server_closed|")) {
        if (FPS_STATE.mp.mode !== "solo") {
          showToast("服务器已关闭（房主退出了游戏）", "error", 3000);
          mpExitGame(true);
        }
        return;
      }
      if (message.startsWith("/mp_bots|")) {
        const on = message.substring(9) === "on";
        FPS_STATE.mp.botsEnabled = on;
        if (FPS_STATE.mp.mode !== "solo") {
          showToast("🤖 机器人刷新已" + (on ? "开启" : "关闭"), "info", 2000);
        }
        return;
      }
      if (message.startsWith("/mp_boss_state|")) {
        const parts = message.split("|", 9);
        if (parts.length === 9 && FPS_STATE.mp.mode !== "solo") {
          applyMpBossState(Number(parts[1]), parts[2] === "1", Number(parts[3]), Number(parts[4]),
            Number(parts[5]), Number(parts[6]), Number(parts[7]), Number(parts[8]));
        }
        return;
      }
      if (message.startsWith("/mp_boss_wave|")) {
        const parts = message.split("|");
        if (parts.length >= 4 && FPS_STATE.mp.mode !== "solo") {
          applyMpBossWave(Number(parts[1]), Number(parts[2]), Number(parts[3]));
        }
        return;
      }
      if (message.startsWith("/mp_bot_state|")) {
        const parts = message.split("|");
        if (parts.length === 7 && FPS_STATE.mp.mode !== "solo") {
          applyMpBotState(decodeBase64Utf8(parts[1]), Number(parts[2]), Number(parts[3]), Number(parts[4]),
            Number(parts[5]), parts[6] === "1");
        }
        return;
      }
      if (message.startsWith("/mp_bot_hit_request|")) {
        const parts = message.split("|");
        if (parts.length === 6 && FPS_STATE.mp.mode === "host") {
          fpsApplyMpBotHitRequest(decodeBase64Utf8(parts[1]), decodeBase64Utf8(parts[2]), Number(parts[3]),
            Number(parts[4]), Number(parts[5]));
        }
        return;
      }
      if (message.startsWith("/mp_bot_remove|")) {
        const parts = message.split("|");
        if (parts.length === 5 && FPS_STATE.mp.mode !== "solo") {
          handleMpBotRemove(decodeBase64Utf8(parts[1]), decodeBase64Utf8(parts[2]), Number(parts[3]), Number(parts[4]));
        }
        return;
      }
      if (message.startsWith("/mp_bot_damage|")) {
        const parts = message.split("|");
        if (parts.length === 4 && FPS_STATE.mp.mode !== "solo") {
          fpsDamagePlayerMp(Number(parts[1]), decodeBase64Utf8(parts[2]), parts[3]);
        }
        return;
      }
      if (message.startsWith("/mp_boss_kill|")) {
        const parts = message.split("|", 4);
        if (parts.length === 4 && FPS_STATE.mp.mode !== "solo") {
          handleMpBossKill(Number(parts[1]), decodeBase64Utf8(parts[2]));
        }
        return;
      }
      if (message.startsWith("/mp_boss_reward|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3 && FPS_STATE.mp.mode !== "solo") {
          fpsGrantMpBossReward(Number(parts[1]), Number(parts[2]));
        }
        return;
      }
      if (message.startsWith("/mp_boss_damage|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3 && FPS_STATE.mp.mode !== "solo") {
          fpsDamagePlayerMp(Number(parts[1]), decodeBase64Utf8(parts[2]), "boss");
        }
        return;
      }
      if (message.startsWith("/mp_kill_reward|")) {
        const parts = message.split("|", 6);
        if (parts.length === 6 && FPS_STATE.mp.mode !== "solo") {
          fpsGrantMpKillReward(Number(parts[1]), Number(parts[2]), Number(parts[3]), Number(parts[4]), decodeBase64Utf8(parts[5] || ""));
        }
        return;
      }
      if (message.startsWith("/mp_hit|")) {
        const parts = message.split("|", 4);
        if (parts.length === 4 && FPS_STATE.mp.mode !== "solo") {
          fpsDamagePlayerMp(Number(parts[2]), decodeBase64Utf8(parts[1]), parts[3]);
        }
        return;
      }
      if (message.startsWith("/mp_melee|")) {
        const parts = message.split("|", 3);
        if (parts.length === 3 && FPS_STATE.mp.mode !== "solo") {
          fpsDamagePlayerMp(75, decodeBase64Utf8(parts[1]), parts[2]);
        }
        return;
      }
      if (message.startsWith("/mp_error|")) {
        showToast(message.substring(10), "error", 3000);
        return;
      }
      if (message.startsWith("/mp_leave_ok")) {
        return;
      }
      if (message !== "/pong" && message !== "/ping") handleOrdinaryMessage(message);
    }

    function renderChannels() {
      ui.channelSelect.replaceChildren();
      if (!state.channels.length) {
        const option = document.createElement("option");
        option.value = "";
        option.textContent = state.channelsComplete ? "没有可用频道" : "正在读取频道...";
        ui.channelSelect.appendChild(option);
        ui.channelSelect.disabled = true;
        ui.loginButton.disabled = true;
        return;
      }
      state.channels.forEach((channel, index) => {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent = channel.name + (channel.requiresPassword ? "（需要密码）" : "");
        ui.channelSelect.appendChild(option);
      });
      ui.channelSelect.disabled = false;
      ui.loginButton.disabled = !state.channelsComplete;
      updatePasswordVisibility();
    }

    function updatePasswordVisibility() {
      const channel = state.channels[Number(ui.channelSelect.value)];
      const required = Boolean(channel && channel.requiresPassword);
      ui.channelPasswordField.hidden = !required;
      ui.channelPasswordInput.required = required;
      if (!required) ui.channelPasswordInput.value = "";
    }

    function beginLogin() {
      const nickname = ui.nicknameInput.value.trim();
      const channel = state.channels[Number(ui.channelSelect.value)];
      ui.loginError.textContent = "";
      if (!channel) return failLogin("请选择频道");
      if (!nickname || !protocolSafe(nickname)) return failLogin("昵称不能包含竖线、换行或控制字符");
      if (new TextEncoder().encode(nickname).length > 30) return failLogin("昵称不能超过 30 字节");
      const password = ui.channelPasswordInput.value;
      if (channel.requiresPassword && !password) return failLogin("请输入频道密码");
      if (!protocolSafe(password)) return failLogin("频道密码包含不支持的字符");
      state.nickname = nickname;
      state.selectedChannel = channel;
      state.loginPhase = "version";
      ui.loginButton.disabled = true;
      ui.channelSelect.disabled = true;
      ui.nicknameInput.disabled = true;
      ui.channelPasswordInput.disabled = true;
      sendProtocol("/version|" + state.clientVersion);
    }

    function continueLoginAfterVersion() {
      if (state.loginPhase !== "version" || !state.selectedChannel) return;
      if (state.selectedChannel.group !== "group_public") {
        state.loginPhase = "password";
        sendProtocol("/login|" + state.selectedChannel.name + "|" + ui.channelPasswordInput.value);
      } else {
        sendSessionIdentity();
      }
    }

    function sendSessionIdentity() {
      if (!state.selectedChannel || state.loginPhase === "identity") return;
      state.loginPhase = "identity";
      sendProtocol("/group|" + state.selectedChannel.group);
      sendProtocol("/nickname|" + state.nickname);
    }

    function failLogin(message) {
      ui.loginError.textContent = message;
      state.loginPhase = "idle";
      ui.loginButton.disabled = !state.channelsComplete || !state.channels.length;
      ui.channelSelect.disabled = !state.channels.length;
      ui.nicknameInput.disabled = false;
      ui.channelPasswordInput.disabled = false;
    }

    function enterChat() {
      state.sessionReady = true;
      state.lastMeaningfulActivity = Date.now();
      ui.historyState.textContent = state.historyReceived ? "已同步" : "已连接";
      ui.topNickname.textContent = state.nickname;
      ui.topChannelName.textContent = state.selectedChannel.name;
      ui.currentChannelName.textContent = state.selectedChannel.name;
      showScreen("app");
      ui.channelMessageInput.focus();
      state.idleTimer = window.setInterval(() => {
        if (Date.now() - state.lastMeaningfulActivity >= IDLE_LIMIT_MS) disconnect("已因一小时未发言而断开连接");
      }, 60000);
    }

    function handleHistory(content) {
      if (content === "start") {
        state.historyReceiving = true;
        state.historyReceived = true;
        ui.historyState.textContent = "历史记录";
        const marker = document.createElement("div");
        marker.className = "history-separator";
        marker.textContent = "历史消息";
        ui.emptyMessages.hidden = true;
        ui.messageFeed.appendChild(marker);
      } else if (content === "end") {
        state.historyReceiving = false;
        ui.historyState.textContent = "已同步";
      } else {
        const parsed = parseChannelLine(content);
        appendMessage(parsed.sender, parsed.text, parsed.sender === state.nickname, parsed.sender ? "channel" : "system", true);
      }
    }

    function parseChannelLine(line) {
      const match = /^\[([^\]]+)]\s?(.*)$/s.exec(line);
      return match ? { sender: match[1], text: match[2] } : { sender: "", text: line };
    }

    function handleOrdinaryMessage(message) {
      const parsed = parseChannelLine(message);
      appendMessage(parsed.sender, parsed.text, parsed.sender === state.nickname, parsed.sender ? "channel" : "system");
    }

    function timeLabel() {
      return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date());
    }

    function createMessageShell(sender, own, kind) {
      const article = document.createElement("article");
      article.className = "message" + (own ? " own" : "") + (kind === "system" ? " system" : "") + (kind === "ai" ? " ai" : "");
      if (kind !== "system") {
        const meta = document.createElement("div");
        meta.className = "message-meta";
        const who = document.createElement("strong");
        who.textContent = sender || "系统";
        const when = document.createElement("span");
        when.textContent = timeLabel();
        meta.append(who, when);
        article.appendChild(meta);
      }
      const body = document.createElement("div");
      body.className = "message-body";
      article.appendChild(body);
      return { article, body };
    }

    function appendMessage(sender, text, own = false, kind = "channel") {
      ui.emptyMessages.hidden = true;
      const shell = createMessageShell(sender, own, kind);
      shell.body.textContent = text;
      ui.messageFeed.appendChild(shell.article);
      scrollToBottom(ui.messageFeed);
    }

    function scrollToBottom(element) {
      window.requestAnimationFrame(() => { element.scrollTop = element.scrollHeight; });
    }

    function sendChannelMessage() {
      const text = ui.channelMessageInput.value.replace(/[\r\n]+/g, " ").trim();
      if (!text || !state.sessionReady) return;
      if (new TextEncoder().encode(text).length > 300) {
        showToast("消息不能超过 300 字节", "error");
        return;
      }
      if (sendProtocol(text, true)) {
        appendMessage(state.nickname, text, true);
        ui.channelMessageInput.value = "";
        autoSizeTextarea(ui.channelMessageInput);
      }
    }

    function renderOnlineUsers() {
      ui.onlineUsers.replaceChildren();
      const users = Array.from(new Set(state.onlineUsers)).filter((name) => name && name !== state.nickname);
      ui.onlineCount.textContent = String(users.length);
      if (!users.length) {
        const empty = document.createElement("div");
        empty.className = "empty-small";
        empty.textContent = "暂无其他在线用户";
        ui.onlineUsers.appendChild(empty);
        if (state.selectedPeerName) selectPeer("");
        return;
      }
      users.forEach((name) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "user-item" + (state.selectedPeerName === name ? " selected" : "");
        const dot = document.createElement("span");
        dot.className = "user-dot";
        const label = document.createElement("span");
        label.className = "user-label";
        label.textContent = name;
        button.append(dot, label);
        button.addEventListener("click", () => selectPeer(name));
        ui.onlineUsers.appendChild(button);
      });
    }

    function selectPeer(name) {
      state.selectedPeerName = name;
      ui.selectedPeer.textContent = name ? "私聊对象：" + name : "请先选择在线用户";
      ui.p2pConnectButton.disabled = !name;
      renderOnlineUsers();
      if (window.matchMedia("(max-width: 820px)").matches && name) switchMobilePanel("tools");
    }

    function requestP2PConnection() {
      const password = ui.peerPasswordInput.value.trim();
      if (!state.selectedPeerName) return showToast("请先选择在线用户", "error");
      if (!/^\d{5}$/.test(password)) return showToast("请输入对方的 5 位数字私聊码", "error");
      state.pendingP2PVerify = { name: state.selectedPeerName, password };
      ui.p2pConnectButton.disabled = true;
      sendProtocol("/p2p_verify|" + password, true);
    }

    function handleP2PVerifyResult(message) {
      const parts = message.split("|", 3);
      ui.p2pConnectButton.disabled = !state.selectedPeerName;
      if (parts[1] === "success" && state.pendingP2PVerify) {
        const pending = state.pendingP2PVerify;
        ensureConversation(pending.name, pending.password);
        appendPrivateMessage(pending.name, "系统", "私聊验证成功", false, true);
        state.pendingP2PVerify = null;
        ui.peerPasswordInput.value = "";
        openPrivateChat(pending.name);
      } else {
        state.pendingP2PVerify = null;
        showToast(parts[2] || "私聊验证失败", "error");
      }
    }

    function ensureConversation(peer, password) {
      let conversation = state.conversations.get(peer);
      if (!conversation) {
        conversation = { peer, password, messages: [] };
        state.conversations.set(peer, conversation);
      } else if (password) {
        conversation.password = password;
      }
      return conversation;
    }

    function appendPrivateMessage(peer, sender, text, own, system) {
      const conversation = ensureConversation(peer, "");
      conversation.messages.push({ sender, text, own, system, time: timeLabel() });
      if (state.activePrivatePeer === peer) renderPrivateMessages();
    }

    function openPrivateChat(peer) {
      const conversation = state.conversations.get(peer);
      if (!conversation) return;
      state.activePrivatePeer = peer;
      ui.privatePeerName.textContent = peer;
      renderPrivateMessages();
      updatePrivateCallUi();
      if (!ui.privateDialog.open) ui.privateDialog.showModal();
      ui.privateMessageInput.focus();
    }

    function renderPrivateMessages() {
      ui.privateFeed.replaceChildren();
      const conversation = state.conversations.get(state.activePrivatePeer);
      if (!conversation || !conversation.messages.length) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "暂无私聊消息";
        ui.privateFeed.appendChild(empty);
        return;
      }
      conversation.messages.forEach((message) => {
        const shell = createMessageShell(message.sender, message.own, message.system ? "system" : "channel");
        shell.body.textContent = message.text;
        ui.privateFeed.appendChild(shell.article);
      });
      scrollToBottom(ui.privateFeed);
    }

    function sendPrivateMessage() {
      const text = ui.privateMessageInput.value.replace(/[\r\n]+/g, " ").trim();
      const conversation = state.conversations.get(state.activePrivatePeer);
      if (!text || !conversation) return;
      if (new TextEncoder().encode(text).length > 300) return showToast("私聊消息不能超过 300 字节", "error");
      if (sendProtocol("/p2p|" + conversation.password + "|" + text, true)) {
        appendPrivateMessage(conversation.peer, state.nickname, text, true, false);
        ui.privateMessageInput.value = "";
      }
    }

    function requestPrivateVoice() {
      const conversation = state.conversations.get(state.activePrivatePeer);
      if (!conversation) return;
      if (state.liveMode || state.liveStarting) return showToast("请先结束当前语音会话", "error");
      state.liveStarting = true;
      state.outgoingCallPeer = conversation.peer;
      updatePrivateCallUi("正在申请语音通话");
      sendProtocol("/live_p2p_request|" + conversation.password, true);
    }

    function handleIncomingCall(message) {
      const parts = message.split("|", 3);
      if (parts.length !== 3) return;
      ensureConversation(parts[1], parts[2]);
      state.incomingCall = { peer: parts[1], password: parts[2] };
      ui.incomingCallText.textContent = parts[1] + " 请求与你进行实时语音通话";
      if (!ui.incomingCallDialog.open) ui.incomingCallDialog.showModal();
    }

    function acceptIncomingCall() {
      if (!state.incomingCall) return;
      const call = state.incomingCall;
      state.incomingCall = null;
      state.liveStarting = true;
      if (ui.incomingCallDialog.open) ui.incomingCallDialog.close();
      openPrivateChat(call.peer);
      updatePrivateCallUi("正在连接语音");
      sendProtocol("/live_p2p_accept|" + call.password, true);
    }

    function rejectIncomingCall() {
      if (!state.incomingCall) return;
      const call = state.incomingCall;
      state.incomingCall = null;
      if (ui.incomingCallDialog.open) ui.incomingCallDialog.close();
      sendProtocol("/live_p2p_reject|" + call.password, true);
      appendPrivateMessage(call.peer, "系统", "已拒绝语音通话申请", false, true);
    }

    function hangupPrivateVoice() {
      if (state.liveMode !== "P2P") return;
      sendProtocol("/live_p2p_end", true);
      cleanupLiveAudio();
      updatePrivateCallUi("语音通话已结束");
    }

    function updatePrivateCallUi(statusText) {
      const peer = state.activePrivatePeer;
      const active = state.liveMode === "P2P" && state.livePeer === peer;
      const pending = state.liveStarting && (state.outgoingCallPeer === peer || !state.outgoingCallPeer);
      ui.privateCallButton.hidden = active;
      ui.privateHangupButton.hidden = !active;
      ui.privateCallButton.disabled = Boolean(state.liveMode || state.liveStarting);
      if (statusText) ui.privateStatus.textContent = statusText;
      else if (active) ui.privateStatus.textContent = "实时语音通话中";
      else if (pending) ui.privateStatus.textContent = "正在等待语音连接";
      else ui.privateStatus.textContent = "私聊已建立";
    }

    function toggleGroupVoice() {
      if (state.liveMode === "GROUP") {
        ui.groupVoiceButton.disabled = true;
        setGroupVoiceUi("pending", "正在退出");
        sendProtocol("/live_group_leave", true);
        return;
      }
      if (state.liveMode || state.liveStarting) return showToast("请先结束当前私聊语音", "error");
      state.liveStarting = true;
      setGroupVoiceUi("pending", "正在加入");
      sendProtocol("/live_group_join", true);
    }

    function setGroupVoiceUi(mode, text) {
      ui.groupVoiceIndicator.className = "voice-indicator" + (mode === "active" ? " active" : mode === "pending" ? " pending" : "");
      ui.groupVoiceStatus.textContent = text;
      ui.groupVoiceButton.textContent = mode === "active" ? "退出频道语音" : "加入频道语音";
      ui.groupVoiceButton.disabled = mode === "pending" || mode === "disabled";
    }

    async function startLiveAudio(mode, peer) {
      if (state.live) return;
      state.liveStarting = true;
      const startToken = {};
      state.liveStartToken = startToken;
      try {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("浏览器只允许在 HTTPS 或 localhost 使用麦克风");
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false },
          video: false
        });
        if (state.liveStartToken !== startToken || !isOpen()) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) throw new Error("当前浏览器不支持实时音频");
        const context = new AudioContextClass({ latencyHint: "interactive" });
        await context.resume();
        if (state.liveStartToken !== startToken || !isOpen()) {
          stream.getTracks().forEach((track) => track.stop());
          await context.close().catch(() => { });
          return;
        }
        const live = {
          stream, context, source: context.createMediaStreamSource(stream), captureNode: null, silentGain: null,
          workletUrl: "", playbackQueue: [], playbackTimer: null, playbackStarted: false,
          nextPlaybackAt: 0, scheduledSources: new Set(), resampleBuffer: new Float32Array(0),
          resamplePosition: 0, frameSamples: [], sourceRate: context.sampleRate
        };
        state.live = live;
        state.liveMode = mode;
        state.livePeer = peer || "";
        await attachLiveCapture(live);
        if (state.liveStartToken !== startToken || !isOpen()) {
          cleanupLiveAudio();
          return;
        }
        live.playbackTimer = window.setInterval(pumpLivePlayback, 10);
        state.liveStarting = false;
        state.liveStartToken = null;
        if (mode === "GROUP") setGroupVoiceUi("active", ui.groupVoiceStatus.textContent === "正在加入" ? "已加入" : ui.groupVoiceStatus.textContent);
        updatePrivateCallUi();
      } catch (error) {
        const cancelled = state.liveStartToken !== startToken;
        cleanupLiveAudio();
        state.liveStarting = false;
        state.liveStartToken = null;
        if (cancelled) return;
        if (mode === "GROUP") {
          sendProtocol("/live_group_leave");
          setGroupVoiceUi("idle", "麦克风不可用");
        } else {
          sendProtocol("/live_p2p_end");
          updatePrivateCallUi("麦克风不可用");
        }
        showToast(error && error.message ? error.message : "无法使用麦克风", "error", 6500);
      }
    }

    async function attachLiveCapture(live) {
      live.silentGain = live.context.createGain();
      live.silentGain.gain.value = 0;
      live.silentGain.connect(live.context.destination);
      if (live.context.audioWorklet && window.AudioWorkletNode) {
        const source = "class PCMInputProcessor extends AudioWorkletProcessor { constructor() { super(); this.parts = []; this.length = 0; } process(inputs) { const input = inputs[0] && inputs[0][0]; if (!input) return true; this.parts.push(input.slice()); this.length += input.length; if (this.length >= 1024) { const merged = new Float32Array(this.length); let offset = 0; for (const part of this.parts) { merged.set(part, offset); offset += part.length; } this.port.postMessage(merged, [merged.buffer]); this.parts = []; this.length = 0; } return true; } } registerProcessor('pcm-input', PCMInputProcessor);";
        live.workletUrl = URL.createObjectURL(new Blob([source], { type: "text/javascript" }));
        await live.context.audioWorklet.addModule(live.workletUrl);
        live.captureNode = new AudioWorkletNode(live.context, "pcm-input", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
        live.captureNode.port.onmessage = (event) => feedLiveSamples(event.data);
      } else {
        live.captureNode = live.context.createScriptProcessor(2048, 1, 1);
        live.captureNode.onaudioprocess = (event) => feedLiveSamples(new Float32Array(event.inputBuffer.getChannelData(0)));
      }
      live.source.connect(live.captureNode);
      live.captureNode.connect(live.silentGain);
    }

    function feedLiveSamples(samples) {
      const live = state.live;
      if (!live || !samples || !samples.length) return;
      const merged = new Float32Array(live.resampleBuffer.length + samples.length);
      merged.set(live.resampleBuffer);
      merged.set(samples, live.resampleBuffer.length);
      const step = live.sourceRate / LIVE_SAMPLE_RATE;
      let position = live.resamplePosition;
      let energy = 0;
      let energyCount = 0;
      while (position + 1 < merged.length) {
        const index = Math.floor(position);
        const fraction = position - index;
        const sample = merged[index] + (merged[index + 1] - merged[index]) * fraction;
        live.frameSamples.push(sample);
        energy += sample * sample;
        energyCount++;
        position += step;
      }
      const consumed = Math.min(Math.floor(position), Math.max(0, merged.length - 1));
      live.resampleBuffer = merged.slice(consumed);
      live.resamplePosition = position - consumed;
      if (energyCount && Math.sqrt(energy / energyCount) > .025) state.lastMeaningfulActivity = Date.now();
      while (live.frameSamples.length >= LIVE_FRAME_SAMPLES) {
        const frame = floatSamplesToPcm(live.frameSamples.splice(0, LIVE_FRAME_SAMPLES));
        const command = state.liveMode === "GROUP" ? "/live_group_audio|" : "/live_p2p_audio|";
        sendProtocol(command + bytesToBase64(frame));
      }
    }

    function floatSamplesToPcm(samples) {
      const bytes = new Uint8Array(samples.length * 2);
      const view = new DataView(bytes.buffer);
      for (let i = 0; i < samples.length; i++) {
        const value = Math.max(-1, Math.min(1, samples[i]));
        view.setInt16(i * 2, value < 0 ? value * 32768 : value * 32767, true);
      }
      return bytes;
    }

    function enqueueLiveFrame(encoded) {
      const live = state.live;
      if (!live) return;
      const bytes = base64ToBytes(encoded);
      if (!bytes || bytes.length !== LIVE_FRAME_BYTES) return;
      if (live.playbackQueue.length >= 15) live.playbackQueue.splice(0, live.playbackQueue.length - 8);
      live.playbackQueue.push(bytes);
    }

    function pumpLivePlayback() {
      const live = state.live;
      if (!live || live.context.state === "closed") return;
      const now = live.context.currentTime;
      if (!live.playbackStarted) {
        if (live.playbackQueue.length < 4) return;
        live.playbackStarted = true;
        live.nextPlaybackAt = now + .08;
      }
      if (live.nextPlaybackAt < now + .015) {
        if (!live.playbackQueue.length) {
          live.playbackStarted = false;
          return;
        }
        live.nextPlaybackAt = now + .06;
      }
      while (live.playbackQueue.length && live.nextPlaybackAt < now + .18) {
        const bytes = live.playbackQueue.shift();
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        const buffer = live.context.createBuffer(1, LIVE_FRAME_SAMPLES, LIVE_SAMPLE_RATE);
        const channel = buffer.getChannelData(0);
        for (let i = 0; i < LIVE_FRAME_SAMPLES; i++) channel[i] = view.getInt16(i * 2, true) / 32768;
        const source = live.context.createBufferSource();
        source.buffer = buffer;
        source.connect(live.context.destination);
        live.scheduledSources.add(source);
        source.onended = () => live.scheduledSources.delete(source);
        source.start(live.nextPlaybackAt);
        live.nextPlaybackAt += LIVE_FRAME_SAMPLES / LIVE_SAMPLE_RATE;
      }
    }

    function cleanupLiveAudio() {
      const live = state.live;
      state.live = null;
      state.liveMode = null;
      state.livePeer = "";
      state.liveStarting = false;
      state.liveStartToken = null;
      if (!live) return;
      if (live.playbackTimer) window.clearInterval(live.playbackTimer);
      live.scheduledSources.forEach((source) => { try { source.stop(); } catch (_) { } });
      try { if (live.source) live.source.disconnect(); } catch (_) { }
      try { if (live.captureNode) live.captureNode.disconnect(); } catch (_) { }
      try { if (live.silentGain) live.silentGain.disconnect(); } catch (_) { }
      live.stream.getTracks().forEach((track) => track.stop());
      if (live.context && live.context.state !== "closed") live.context.close().catch(() => { });
      if (live.workletUrl) URL.revokeObjectURL(live.workletUrl);
      setGroupVoiceUi("idle", "未加入");
      updatePrivateCallUi();
    }

    async function toggleVoiceNote() {
      if (state.note) {
        await stopVoiceNote(true);
        return;
      }
      if (state.liveMode || state.liveStarting) return showToast("实时语音期间不能录制语音留言", "error");
      try {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("浏览器只允许在 HTTPS 或 localhost 使用麦克风");
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false },
          video: false
        });
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        const context = new AudioContextClass();
        await context.resume();
        const source = context.createMediaStreamSource(stream);
        const processor = context.createScriptProcessor(2048, 1, 1);
        const silent = context.createGain();
        silent.gain.value = 0;
        const note = { stream, context, source, processor, silent, chunks: [], startedAt: Date.now(), timer: null, stopping: false };
        state.note = note;
        processor.onaudioprocess = (event) => note.chunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
        source.connect(processor);
        processor.connect(silent);
        silent.connect(context.destination);
        ui.voiceNoteButton.classList.add("recording");
        ui.voiceNoteButton.title = "停止并发送语音留言";
        note.timer = window.setInterval(() => {
          const seconds = Math.min(8, Math.floor((Date.now() - note.startedAt) / 1000));
          ui.composerStatus.textContent = "正在录音 " + seconds + " / 8 秒";
          if (seconds >= 8) stopVoiceNote(true);
        }, 250);
      } catch (error) {
        showToast(error && error.message ? error.message : "无法使用麦克风", "error", 6500);
      }
    }

    async function stopVoiceNote(send) {
      const note = state.note;
      if (!note || note.stopping) return;
      note.stopping = true;
      state.note = null;
      if (note.timer) window.clearInterval(note.timer);
      ui.voiceNoteButton.classList.remove("recording");
      ui.voiceNoteButton.title = "录制语音留言";
      ui.composerStatus.textContent = "";
      try { note.source.disconnect(); } catch (_) { }
      try { note.processor.disconnect(); } catch (_) { }
      try { note.silent.disconnect(); } catch (_) { }
      note.stream.getTracks().forEach((track) => track.stop());
      const inputRate = note.context.sampleRate;
      if (note.context.state !== "closed") await note.context.close().catch(() => { });
      if (!send || !note.chunks.length || Date.now() - note.startedAt < 250) return;
      const samples = mergeFloatChunks(note.chunks);
      const downsampled = downsampleAudio(samples, inputRate, LIVE_SAMPLE_RATE);
      const wav = encodeWav(downsampled, LIVE_SAMPLE_RATE);
      const id = "voice_" + Date.now() + "_web";
      if (sendProtocol("/voice|" + id + "|" + bytesToBase64(wav), true)) {
        appendVoiceElement(state.nickname, id, wav, true);
        showToast("语音留言已发送", "success");
      }
    }

    function mergeFloatChunks(chunks) {
      const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
      const result = new Float32Array(length);
      let offset = 0;
      chunks.forEach((chunk) => { result.set(chunk, offset); offset += chunk.length; });
      return result;
    }

    function downsampleAudio(input, inputRate, outputRate) {
      if (inputRate === outputRate) return input;
      const ratio = inputRate / outputRate;
      const output = new Float32Array(Math.floor(input.length / ratio));
      for (let i = 0; i < output.length; i++) {
        const start = Math.floor(i * ratio);
        const end = Math.max(start + 1, Math.min(input.length, Math.floor((i + 1) * ratio)));
        let sum = 0;
        for (let j = start; j < end; j++) sum += input[j];
        output[i] = sum / (end - start);
      }
      return output;
    }

    function encodeWav(samples, sampleRate) {
      const bytes = new Uint8Array(44 + samples.length * 2);
      const view = new DataView(bytes.buffer);
      const writeText = (offset, text) => { for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i)); };
      writeText(0, "RIFF");
      view.setUint32(4, 36 + samples.length * 2, true);
      writeText(8, "WAVE");
      writeText(12, "fmt ");
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true);
      view.setUint16(22, 1, true);
      view.setUint32(24, sampleRate, true);
      view.setUint32(28, sampleRate * 2, true);
      view.setUint16(32, 2, true);
      view.setUint16(34, 16, true);
      writeText(36, "data");
      view.setUint32(40, samples.length * 2, true);
      for (let i = 0; i < samples.length; i++) {
        const value = Math.max(-1, Math.min(1, samples[i]));
        view.setInt16(44 + i * 2, value < 0 ? value * 32768 : value * 32767, true);
      }
      return bytes;
    }

    function rawPcmToWav(bytes) {
      const samples = new Float32Array(Math.floor(bytes.length / 2));
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      for (let i = 0; i < samples.length; i++) samples[i] = view.getInt16(i * 2, true) / 32768;
      return encodeWav(samples, LIVE_SAMPLE_RATE);
    }

    function addVoiceMessage(sender, id, encoded, own) {
      const bytes = base64ToBytes(encoded);
      if (!bytes || !bytes.length) return;
      const isWav = bytes.length >= 12 && String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF" && String.fromCharCode(...bytes.subarray(8, 12)) === "WAVE";
      appendVoiceElement(sender, id, isWav ? bytes : rawPcmToWav(bytes), own);
    }

    function appendVoiceElement(sender, id, wav, own) {
      ui.emptyMessages.hidden = true;
      const shell = createMessageShell(sender, own, "channel");
      const audio = document.createElement("audio");
      audio.controls = true;
      audio.preload = "metadata";
      audio.setAttribute("aria-label", "语音留言 " + id);
      const url = URL.createObjectURL(new Blob([wav], { type: "audio/wav" }));
      state.objectUrls.add(url);
      audio.src = url;
      shell.body.appendChild(audio);
      ui.messageFeed.appendChild(shell.article);
      scrollToBottom(ui.messageFeed);
    }

    async function sendSelectedImage(file) {
      if (!file) return;
      if (!file.type.startsWith("image/")) return showToast("请选择图片文件", "error");
      if (file.size > MAX_IMAGE_BYTES) return showToast("图片不能超过 20MB", "error");
      ui.imageButton.disabled = true;
      ui.composerStatus.textContent = "正在发送图片";
      try {
        const bytes = new Uint8Array(await file.arrayBuffer());
        const encoded = bytesToBase64(bytes);
        const total = Math.ceil(encoded.length / IMAGE_CHUNK_SIZE);
        const id = "img_" + Date.now() + "_web";
        const safeName = file.name.replace(/[|\r\n]/g, "_") || "image";
        sendProtocol("/image_info|" + id + "|" + total + "|" + safeName, true);
        for (let i = 0; i < total; i++) {
          await waitForWebSocketBuffer();
          sendProtocol("/image_chunk|" + id + "|" + i + "|" + encoded.slice(i * IMAGE_CHUNK_SIZE, (i + 1) * IMAGE_CHUNK_SIZE));
        }
        appendImageElement(state.nickname, safeName, bytes, file.type, true);
        showToast("图片已发送", "success");
      } catch (_) {
        showToast("图片发送失败", "error");
      } finally {
        ui.imageInput.value = "";
        ui.imageButton.disabled = false;
        ui.composerStatus.textContent = "";
      }
    }

    function waitForWebSocketBuffer() {
      return new Promise((resolve, reject) => {
        const check = () => {
          if (!isOpen()) return reject(new Error("socket closed"));
          if (state.socket.bufferedAmount < 512000) resolve();
          else window.setTimeout(check, 20);
        };
        check();
      });
    }

    function handleImageInfo(message) {
      const parts = message.split("|", 4);
      const total = Number(parts[2]);
      if (parts.length !== 4 || !Number.isInteger(total) || total < 1 || total > 1000) return;
      state.imageReceivers.set(parts[1], { filename: parts[3], total, chunks: new Array(total), received: 0 });
      appendMessage("系统", "正在接收图片：" + parts[3], false, "system");
    }

    function handleImageChunk(message) {
      const parts = message.split("|", 4);
      if (parts.length !== 4) return;
      const receiver = state.imageReceivers.get(parts[1]);
      const index = Number(parts[2]);
      if (!receiver || !Number.isInteger(index) || index < 0 || index >= receiver.total || receiver.chunks[index] !== undefined) return;
      receiver.chunks[index] = parts[3];
      receiver.received++;
      if (receiver.received !== receiver.total) return;
      state.imageReceivers.delete(parts[1]);
      const bytes = base64ToBytes(receiver.chunks.join(""));
      if (!bytes) return showToast("收到的图片数据无效", "error");
      appendImageElement("频道用户", receiver.filename, bytes, mimeFromFilename(receiver.filename), false);
    }

    function mimeFromFilename(filename) {
      const extension = (filename.split(".").pop() || "").toLowerCase();
      return ({ png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", bmp: "image/bmp" })[extension] || "application/octet-stream";
    }

    function appendImageElement(sender, filename, bytes, mime, own) {
      ui.emptyMessages.hidden = true;
      const shell = createMessageShell(sender, own, "channel");
      const image = document.createElement("img");
      const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
      state.objectUrls.add(url);
      image.src = url;
      image.alt = filename;
      image.loading = "lazy";
      shell.body.appendChild(image);
      ui.messageFeed.appendChild(shell.article);
      scrollToBottom(ui.messageFeed);
    }

    function askDeepSeek() {
      const question = ui.deepSeekInput.value.replace(/[\r\n]+/g, " ").trim();
      if (!question) return;
      if (new TextEncoder().encode(question).length > 300) return showToast("问题不能超过 300 字节", "error");
      sendProtocol("/deepseek|" + question, true);
      appendMessage(state.nickname, question, true, "channel");
      ui.deepSeekInput.value = "";
      showToast("问题已发送", "success");
    }

    function autoSizeTextarea(textarea) {
      textarea.style.height = "auto";
      textarea.style.height = Math.min(textarea.scrollHeight, 116) + "px";
    }

    function switchMobilePanel(panel) {
      ui.workspace.dataset.mobilePanel = panel;
      document.querySelectorAll(".mobile-tabs button").forEach((button) => button.classList.toggle("active", button.dataset.panel === panel));
    }


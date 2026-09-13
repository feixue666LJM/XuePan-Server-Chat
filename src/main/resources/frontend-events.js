    ui.verifyForm.addEventListener("submit", (event) => {
      event.preventDefault();
      const answer = ui.verifyAnswer.value.trim();
      if (!answer) return;
      ui.verifyButton.disabled = true;
      ui.verifyError.textContent = "";
      sendProtocol("/web_verify|" + encodeBase64Utf8(answer));
    });
    ui.reconnectButton.addEventListener("click", connectWebSocket);
    ui.channelSelect.addEventListener("change", updatePasswordVisibility);
    ui.loginForm.addEventListener("submit", (event) => { event.preventDefault(); beginLogin(); });
    ui.disconnectButton.addEventListener("click", () => disconnect("已主动断开连接"));
    ui.gamesButton.addEventListener("click", enterGames);
    document.getElementById("webPanButton").addEventListener("click", enterWebPan);
    document.getElementById("webPanBackButton").addEventListener("click", () => {
      showScreen(state.sessionReady ? "app" : state.verified ? "login" : "gate");
    });
    document.getElementById("webPanRefreshButton").addEventListener("click", () => {
      if (state.verified && isOpen()) ui.webPanFrame.contentWindow.location.reload();
    });
    ui.webPanFrame.addEventListener("load", () => {
      if (ui.webPanScreen.hidden || ui.webPanFrame.src === "about:blank") return;
      const page = ui.webPanFrame.contentDocument;
      if (page && (page.getElementById("gateScreen") || page.getElementById("webPanVerificationRequired"))) {
        state.webPanPending = true;
        disconnect("网盘验证已失效，请重新连接并验证");
      }
    });
    ui.gamesBackButton.addEventListener("click", leaveGames);
    ui.snakeBackButton.addEventListener("click", leaveSnake);
    ui.snakeRestartButton.addEventListener("click", initSnakeGame);
    ui.clickBackButton.addEventListener("click", leaveClick);
    ui.clickStartButton.addEventListener("click", startClickGame);
    ui.clickRestartButton.addEventListener("click", restartClickGame);
    ui.clickCanvas.addEventListener("click", handleClickCanvasClick);
    ui.earthBackButton.addEventListener("click", leaveEarth);
    ui.earthStageBack.addEventListener("click", leaveEarth);
    ui.earthModeNone.addEventListener("click", () => setEarthMode("none"));
    ui.earthModeRain.addEventListener("click", () => setEarthMode("rain"));
    ui.earthModeSnow.addEventListener("click", () => setEarthMode("snow"));
    ui.earthModeMeteor.addEventListener("click", () => setEarthMode("meteor"));
    ui.earthModeConflict.addEventListener("click", () => setEarthMode("conflict"));
    ui.earthZoom.addEventListener("input", () => {
      EARTH_STATE.zoomTargetDist = EARTH_CAMERA_DIST + (Number(ui.earthZoom.value) / 100) * (EARTH_ZOOM_MAX_DIST - EARTH_CAMERA_DIST);
    });
    ui.fpsStartButton.addEventListener("click", fpsStart);
    ui.fpsBackButton.addEventListener("click", leaveFps);
    ui.fpsSettleBackButton.addEventListener("click", () => {
      ui.fpsSettle.hidden = true;
      ui.fpsMenu.hidden = false;
      ui.fpsBestScore.textContent = String(fpsBest());
      FPS_STATE.phase = "menu";
    });
    ui.fpsMpButton.addEventListener("click", mpOpenPanel);
    ui.fpsMpBackButton.addEventListener("click", mpClosePanel);
    ui.fpsMpRefreshButton.addEventListener("click", mpRefreshList);
    ui.fpsMpCreateButton.addEventListener("click", mpCreate);
    ui.fpsResumeButton.addEventListener("click", fpsResume);
    ui.fpsPauseExitButton.addEventListener("click", fpsPauseExit);
    window.addEventListener("keydown", handleSnakeKey);
    ui.snakeCanvas.addEventListener("touchstart", handleSnakeTouchStart, { passive: true });
    ui.snakeCanvas.addEventListener("touchmove", handleSnakeTouchMove, { passive: true });
    ui.snakeCanvas.addEventListener("touchend", handleSnakeTouchEnd, { passive: true });
    ui.channelMessageForm.addEventListener("submit", (event) => { event.preventDefault(); sendChannelMessage(); });
    ui.channelMessageInput.addEventListener("input", () => autoSizeTextarea(ui.channelMessageInput));
    ui.channelMessageInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); sendChannelMessage(); }
    });
    ui.imageButton.addEventListener("click", () => ui.imageInput.click());
    ui.imageInput.addEventListener("change", () => sendSelectedImage(ui.imageInput.files[0]));
    ui.voiceNoteButton.addEventListener("click", toggleVoiceNote);
    ui.groupVoiceButton.addEventListener("click", toggleGroupVoice);
    ui.p2pConnectForm.addEventListener("submit", (event) => { event.preventDefault(); requestP2PConnection(); });
    ui.deepSeekForm.addEventListener("submit", (event) => { event.preventDefault(); askDeepSeek(); });
    ui.privateMessageForm.addEventListener("submit", (event) => { event.preventDefault(); sendPrivateMessage(); });
    ui.privateCallButton.addEventListener("click", requestPrivateVoice);
    ui.privateHangupButton.addEventListener("click", hangupPrivateVoice);
    ui.privateCloseButton.addEventListener("click", () => ui.privateDialog.close());
    ui.acceptCallButton.addEventListener("click", acceptIncomingCall);
    ui.rejectCallButton.addEventListener("click", rejectIncomingCall);
    ui.incomingCallDialog.addEventListener("cancel", (event) => { event.preventDefault(); rejectIncomingCall(); });
    document.querySelectorAll(".mobile-tabs button").forEach((button) => button.addEventListener("click", () => switchMobilePanel(button.dataset.panel)));

    window.addEventListener("beforeunload", () => {
      if (isOpen()) {
        try { state.socket.send("/web_disconnect"); } catch (_) { }
        state.socket.close();
      }
      state.objectUrls.forEach((url) => URL.revokeObjectURL(url));
    });

    connectWebSocket();

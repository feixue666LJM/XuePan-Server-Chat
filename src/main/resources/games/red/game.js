    // ==================== 点格子 ====================
    function enterClick() {
      showScreen("click");
      initClickGame();
      requestGameRank(CLICK_RECORDS_FILE, "点格子");
    }

    function leaveClick() {
      const game = clickGame.game;
      if (game && game.started && !game.over) {
        game.over = true;
        stopClickTimer();
        saveGameRecord(CLICK_RECORDS_FILE, "点格子", game.score);
      }
      stopClickTimer();
      showScreen("games");
    }

    function stopClickTimer() {
      if (clickGame.timer) {
        window.clearInterval(clickGame.timer);
        clickGame.timer = null;
      }
    }

    function initClickGame() {
      stopClickTimer();
      const canvas = ui.clickCanvas;
      clickGame.ctx = canvas.getContext("2d");
      clickGame.game = {
        cells: [],
        nextSpawnAt: 0,
        score: 0,
        started: false,
        over: false
      };
      ui.clickScore.textContent = "0";
      ui.clickStatus.textContent = "点击开始游戏";
      // 就绪状态：不自动出格子，等待玩家点击“开始游戏”
      ui.clickOverlayTitle.textContent = "点格子";
      ui.clickOverlayScore.textContent = "20 × 20 · 每 0.5 秒出一个红格，3 秒内没点中就结束";
      ui.clickStartButton.hidden = false;
      ui.clickRestartButton.hidden = true;
      ui.clickOverlay.hidden = false;
      drawClick();
    }

    function startClickGame() {
      const game = clickGame.game;
      if (!game || game.over) return;
      ui.clickOverlay.hidden = true;
      ui.clickStatus.textContent = "游戏开始！";
      game.nextSpawnAt = Date.now(); // 立即出现第一个红色格子，之后每 500ms 一个
      clickGame.timer = window.setInterval(clickTick, 50);
    }

    // 再来一局：重置到就绪状态后立即开始
    function restartClickGame() {
      initClickGame();
      startClickGame();
    }

    function clickTick() {
      const game = clickGame.game;
      if (!game || game.over) return;
      const now = Date.now();
      // 固定节奏：上一个红格出现 500ms 后出现下一个（与是否点击无关）
      if (now >= game.nextSpawnAt) spawnClickCell();
      // 任何一个红格超过 3 秒没被点到，游戏结束
      for (let i = 0; i < game.cells.length; i++) {
        if (now - game.cells[i].at >= CLICK_WINDOW_MS) {
          endClickGame();
          return;
        }
      }
      drawClick();
    }

    function spawnClickCell() {
      const game = clickGame.game;
      if (!game || game.over) return;
      const occupied = new Set(game.cells.map((cell) => cell.x + "," + cell.y));
      let x = Math.floor(Math.random() * CLICK_GRID);
      let y = Math.floor(Math.random() * CLICK_GRID);
      let attempts = 0;
      while (occupied.has(x + "," + y) && attempts < 12) {
        x = Math.floor(Math.random() * CLICK_GRID);
        y = Math.floor(Math.random() * CLICK_GRID);
        attempts++;
      }
      game.cells.push({ x, y, at: Date.now() });
      game.started = true;
      game.nextSpawnAt = Date.now() + CLICK_SPAWN_DELAY_MS;
      ui.clickStatus.textContent = "快点击红色格子！";
    }

    function handleClickCanvasClick(event) {
      const game = clickGame.game;
      if (!game || game.over || !game.cells.length) return;
      const rect = ui.clickCanvas.getBoundingClientRect();
      const scale = ui.clickCanvas.width / rect.width;
      const x = Math.floor((event.clientX - rect.left) * scale / CLICK_CELL);
      const y = Math.floor((event.clientY - rect.top) * scale / CLICK_CELL);
      for (let i = game.cells.length - 1; i >= 0; i--) {
        const cell = game.cells[i];
        if (Date.now() - cell.at >= CLICK_WINDOW_MS) continue; // 已超时的格子不响应
        if (x === cell.x && y === cell.y) {
          game.cells.splice(i, 1);
          game.score += 1;
          ui.clickScore.textContent = String(game.score);
          ui.clickStatus.textContent = "快点击红色格子！";
          return;
        }
      }
    }

    function drawClick() {
      const ctx = clickGame.ctx;
      const game = clickGame.game;
      if (!ctx || !game) return;
      const size = CLICK_GRID * CLICK_CELL;
      ctx.fillStyle = "#0f1412";
      ctx.fillRect(0, 0, size, size);
      ctx.strokeStyle = "rgba(255,255,255,.08)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let i = 1; i < CLICK_GRID; i++) {
        ctx.moveTo(i * CLICK_CELL, 0);
        ctx.lineTo(i * CLICK_CELL, size);
        ctx.moveTo(0, i * CLICK_CELL);
        ctx.lineTo(size, i * CLICK_CELL);
      }
      ctx.stroke();
      game.cells.forEach((cell) => {
        const remaining = Math.max(0, 1 - (Date.now() - cell.at) / CLICK_WINDOW_MS);
        ctx.fillStyle = "#ff5252";
        ctx.fillRect(cell.x * CLICK_CELL + 3, cell.y * CLICK_CELL + 3, CLICK_CELL - 6, CLICK_CELL - 6);
        ctx.strokeStyle = "#ffe3e3";
        ctx.lineWidth = 4;
        ctx.beginPath();
        ctx.arc(cell.x * CLICK_CELL + CLICK_CELL / 2, cell.y * CLICK_CELL + CLICK_CELL / 2,
          CLICK_CELL * .5 - 2, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * remaining);
        ctx.stroke();
      });
    }

    function endClickGame() {
      const game = clickGame.game;
      if (!game || game.over) return;
      game.over = true;
      stopClickTimer();
      ui.clickStatus.textContent = "游戏结束";
      ui.clickOverlayTitle.textContent = "游戏结束";
      ui.clickOverlayScore.textContent = "得分：" + game.score;
      ui.clickStartButton.hidden = true;
      ui.clickRestartButton.hidden = false;
      ui.clickOverlay.hidden = false;
      saveGameRecord(CLICK_RECORDS_FILE, "点格子", game.score);
    }


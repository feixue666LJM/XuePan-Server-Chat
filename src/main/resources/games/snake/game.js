    // ==================== 小游戏 ====================
    const GAMES = [
      { id: "snake", name: "贪吃蛇", icon: "🐍", desc: "35 × 35 经典贪吃蛇，吃到食物得分，撞墙或撞到自己结束" },
      { id: "click", name: "点格子", icon: "🔴", desc: "20 × 20 每 0.5 秒随机出现一个红格，3 秒内没点中就结束" },
      { id: "earth", name: "模拟地球", icon: "🌍", desc: "3D 地球 · 大洲/城市人口实时增长，上帝可呼风唤雨降陨石" },
      { id: "fps", name: "3D射击生存", icon: "🔫", desc: "第一人称射击 · 对抗无限刷新的近战机器人，生存得分" },
      { id: "decrypt", name: "解密（未做完）", icon: "🔎", desc: "点击场景中的高亮区域进行调查，搜集线索并解开谜题（开发中）" }
    ];

    const SNAKE_RECORDS_FILE = "game_records.json";
    const CLICK_RECORDS_FILE = "buui.json";
    const SNAKE_GRID = 35;
    const SNAKE_CELL = 20;
    const CLICK_GRID = 20;
    const CLICK_CELL = 35;
    const CLICK_WINDOW_MS = 3000;
    const CLICK_SPAWN_DELAY_MS = 500;

    const snake = {
      game: null,
      ctx: null,
      timer: null,
      touchStart: null
    };

    const clickGame = {
      game: null,
      ctx: null,
      timer: null
    };

    function enterGames() {
      ui.gamesCount.textContent = "一共有：" + GAMES.length + "个游戏";
      ui.gameList.replaceChildren();
      GAMES.forEach((game) => {
        const card = document.createElement("button");
        card.type = "button";
        card.className = "game-card";
        const icon = document.createElement("span");
        icon.className = "game-card-icon";
        icon.textContent = game.icon;
        const info = document.createElement("span");
        info.className = "game-card-info";
        const name = document.createElement("strong");
        name.textContent = game.name;
        const desc = document.createElement("span");
        desc.textContent = game.desc;
        info.append(name, desc);
        card.append(icon, info);
        card.addEventListener("click", () => enterGame(game.id));
        ui.gameList.appendChild(card);
      });
      showScreen("games");
    }

    function leaveGames() {
      showScreen("app");
    }

    function enterDecrypt() {
      showScreen("decrypt");
      if (ui.decryptFrame.getAttribute("src") === "about:blank") {
        ui.decryptFrame.src = "/games/decrypt/game2.0.html";
      }
    }

    function leaveDecrypt() {
      showScreen("games");
    }

    function enterGame(id) {
      if (id === "snake") enterSnake();
      else if (id === "click") enterClick();
      else if (id === "earth") enterEarth();
      else if (id === "fps") enterFps();
      else if (id === "decrypt") enterDecrypt();
    }

    function enterSnake() {
      showScreen("snake");
      initSnakeGame();
      requestGameRank(SNAKE_RECORDS_FILE, "贪吃蛇");
    }

    function leaveSnake() {
      const game = snake.game;
      if (game && game.running && !game.over) {
        game.running = false;
        game.over = true;
        stopSnakeTimer();
        saveGameRecord(SNAKE_RECORDS_FILE, "贪吃蛇", game.score);
      }
      stopSnakeTimer();
      showScreen("games");
    }

    function stopSnakeTimer() {
      if (snake.timer) {
        window.clearInterval(snake.timer);
        snake.timer = null;
      }
    }

    function initSnakeGame() {
      stopSnakeTimer();
      const canvas = ui.snakeCanvas;
      snake.ctx = canvas.getContext("2d");
      const center = Math.floor(SNAKE_GRID / 2);
      snake.game = {
        cells: [
          { x: center, y: center },
          { x: center - 1, y: center },
          { x: center - 2, y: center }
        ],
        dir: { x: 1, y: 0 },
        queue: [],
        food: null,
        score: 0,
        running: false,
        over: false,
        tickMs: 130
      };
      ui.snakeScore.textContent = "0";
      ui.snakeStatus.textContent = "按方向键开始";
      ui.snakeOverlay.hidden = true;
      placeSnakeFood();
      drawSnake();
    }

    function setSnakeDirection(dx, dy) {
      const game = snake.game;
      if (!game || game.over) return;
      const last = game.queue.length ? game.queue[game.queue.length - 1] : game.dir;
      if (last.x === -dx && last.y === -dy) return; // 不能直接掉头
      if (last.x === dx && last.y === dy) return;   // 相同方向忽略
      if (game.queue.length >= 3) return;
      game.queue.push({ x: dx, y: dy });
      if (!game.running) {
        game.running = true;
        ui.snakeStatus.textContent = "游戏中";
        snake.timer = window.setInterval(snakeTick, game.tickMs);
      }
    }

    function snakeTick() {
      const game = snake.game;
      if (!game || !game.running) return;
      if (game.queue.length) game.dir = game.queue.shift();
      const head = game.cells[0];
      const next = { x: head.x + game.dir.x, y: head.y + game.dir.y };
      if (next.x < 0 || next.x >= SNAKE_GRID || next.y < 0 || next.y >= SNAKE_GRID) return endSnakeGame();
      const willGrow = Boolean(game.food) && next.x === game.food.x && next.y === game.food.y;
      for (let i = 0; i < game.cells.length; i++) {
        if (game.cells[i].x === next.x && game.cells[i].y === next.y) {
          if (!willGrow && i === game.cells.length - 1) continue; // 尾部即将移走
          return endSnakeGame();
        }
      }
      game.cells.unshift(next);
      if (willGrow) {
        game.score += 1;
        ui.snakeScore.textContent = String(game.score);
        placeSnakeFood();
        if (game.score % 5 === 0 && game.tickMs > 60) {
          game.tickMs = Math.max(60, game.tickMs - 10);
          stopSnakeTimer();
          snake.timer = window.setInterval(snakeTick, game.tickMs);
        }
      } else {
        game.cells.pop();
      }
      drawSnake();
    }

    function placeSnakeFood() {
      const game = snake.game;
      const occupied = new Set(game.cells.map((cell) => cell.x + "," + cell.y));
      const free = [];
      for (let y = 0; y < SNAKE_GRID; y++) {
        for (let x = 0; x < SNAKE_GRID; x++) {
          if (!occupied.has(x + "," + y)) free.push({ x, y });
        }
      }
      if (!free.length) {
        endSnakeGame(); // 蛇填满全部格子，胜利
        return;
      }
      game.food = free[Math.floor(Math.random() * free.length)];
    }

    function drawSnake() {
      const ctx = snake.ctx;
      const game = snake.game;
      if (!ctx || !game) return;
      const size = SNAKE_GRID * SNAKE_CELL;
      ctx.fillStyle = "#0f1412";
      ctx.fillRect(0, 0, size, size);
      ctx.strokeStyle = "rgba(255,255,255,.05)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let i = 1; i < SNAKE_GRID; i++) {
        ctx.moveTo(i * SNAKE_CELL, 0);
        ctx.lineTo(i * SNAKE_CELL, size);
        ctx.moveTo(0, i * SNAKE_CELL);
        ctx.lineTo(size, i * SNAKE_CELL);
      }
      ctx.stroke();
      if (game.food) {
        ctx.fillStyle = "#ff5252";
        ctx.beginPath();
        ctx.arc(game.food.x * SNAKE_CELL + SNAKE_CELL / 2, game.food.y * SNAKE_CELL + SNAKE_CELL / 2, SNAKE_CELL * .32, 0, Math.PI * 2);
        ctx.fill();
      }
      game.cells.forEach((cell, index) => {
        const isHead = index === 0;
        ctx.fillStyle = isHead ? "#5ce08c" : "#2cb96d";
        const pad = isHead ? 1 : 2;
        ctx.fillRect(cell.x * SNAKE_CELL + pad, cell.y * SNAKE_CELL + pad, SNAKE_CELL - pad * 2, SNAKE_CELL - pad * 2);
      });
    }

    function endSnakeGame() {
      const game = snake.game;
      if (!game || game.over) return;
      game.over = true;
      game.running = false;
      stopSnakeTimer();
      ui.snakeStatus.textContent = "游戏结束";
      ui.snakeOverlayTitle.textContent = game.cells.length >= SNAKE_GRID * SNAKE_GRID ? "你赢了！" : "游戏结束";
      ui.snakeOverlayScore.textContent = "得分：" + game.score;
      ui.snakeOverlay.hidden = false;
      saveGameRecord(SNAKE_RECORDS_FILE, "贪吃蛇", game.score);
    }

    function saveGameRecord(recordFile, gamesName, score) {
      if (!state.sessionReady) return;
      if (!protocolSafe(recordFile) || !protocolSafe(gamesName) || !Number.isInteger(score) || score < 0) return;
      if (sendProtocol("/game_record|" + recordFile + "|" + gamesName + "|" + score, true)) {
        showToast("战绩已保存", "success", 2500);
        requestGameRank(recordFile, gamesName);
      }
    }

    function requestGameRank(recordFile, gamesName) {
      if (!state.sessionReady) return;
      if (!protocolSafe(recordFile) || !protocolSafe(gamesName)) return;
      sendProtocol("/game_rank|" + recordFile + "|" + gamesName);
    }

    function renderRankList(listEl, rows, myName) {
      listEl.replaceChildren();
      if (!rows.length) {
        const empty = document.createElement("div");
        empty.className = "rank-empty";
        empty.textContent = "暂无记录，快来抢占第一名！";
        listEl.appendChild(empty);
        return;
      }
      rows.forEach((row) => {
        const item = document.createElement("div");
        item.className = "rank-row" + (row.rank <= 3 ? " top" + row.rank : "") + (row.id === myName ? " self" : "");
        const no = document.createElement("span");
        no.className = "rank-no";
        no.textContent = row.rank <= 3 ? ["🥇", "🥈", "🥉"][row.rank - 1] : String(row.rank);
        const name = document.createElement("span");
        name.className = "rank-name";
        name.textContent = row.id === myName ? row.id + "（我）" : row.id;
        const score = document.createElement("span");
        score.className = "rank-score";
        score.textContent = String(row.score);
        item.append(no, name, score);
        listEl.appendChild(item);
      });
    }

    function handleSnakeKey(event) {
      if (ui.snakeScreen.hidden) return;
      let dx = 0, dy = 0;
      const key = event.key;
      if (key === "ArrowUp" || key === "w" || key === "W") dy = -1;
      else if (key === "ArrowDown" || key === "s" || key === "S") dy = 1;
      else if (key === "ArrowLeft" || key === "a" || key === "A") dx = -1;
      else if (key === "ArrowRight" || key === "d" || key === "D") dx = 1;
      else return;
      event.preventDefault();
      setSnakeDirection(dx, dy);
    }

    function handleSnakeTouchStart(event) {
      const touch = event.touches[0];
      snake.touchStart = { x: touch.clientX, y: touch.clientY };
    }

    function handleSnakeTouchMove(event) {
      if (!snake.touchStart) return;
      const touch = event.touches[0];
      const dx = touch.clientX - snake.touchStart.x;
      const dy = touch.clientY - snake.touchStart.y;
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 24) return;
      if (Math.abs(dx) > Math.abs(dy)) setSnakeDirection(dx > 0 ? 1 : -1, 0);
      else setSnakeDirection(0, dy > 0 ? 1 : -1);
      snake.touchStart = { x: touch.clientX, y: touch.clientY };
    }

    function handleSnakeTouchEnd() {
      snake.touchStart = null;
    }


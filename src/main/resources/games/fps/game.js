    // ==================== 3D 射击生存（FPS） ====================
    const IS_TOUCH_DEVICE = typeof window !== "undefined"
      && ("ontouchstart" in window || (navigator.maxTouchPoints || 0) > 0);
    const FPS_RECORD_FILE = "fps.json";
    const FPS_GAME_NAME = "3D射击生存";
    const FPS_BEST_KEY = "fps_survival_best";
    const FPS_DEVTOOLS_GUARD_KEY = "fps_devtools_refresh_guard";
    const FPS_DEVTOOLS_SIZE_THRESHOLD = 160;
    const FPS_DEVTOOLS_CHECK_INTERVAL = 500;
    const FPS_PLAYER = { hp: 150, speed: 5.2, crouchSpeed: 3.6, jumpVel: 5.6, gravity: 11, eyeStand: 1.62, eyeCrouch: 0.95, heightStand: 1.8, heightCrouch: 1.05, radius: 0.36 };
    const FPS_REMOTE_PLAYER_SCALE = 1.08;
    // 武器配置数组。枪械状态会保存当前配置，确保单人和多人模式使用同一套属性。
    const WEAPONS = [
      {
        name: "手枪",
        magSize: 8,
        reserve: 24,
        fireRate: 2,
        damageBody: 14,
        damageHead: 50,
        color: 0x2b2f33,
        muzzleColor: 0x202428
      },
      {
        name: "UZI",
        magSize: 25,
        reserve: 50,
        fireRate: 12,
        damageBody: 22,
        damageHead: 70,
        color: 0x1a1e22,
        muzzleColor: 0x4a3a2a
      }
    ].concat(Array.isArray(window.FPS_EXTENDED_WEAPONS) ? window.FPS_EXTENDED_WEAPONS : []);
    const FPS_RELOAD_TIME = 1.5;
    const FPS_MP_MUSIC_PATH = "/music/game4.mp3";
    const FPS_MP_MUSIC_VOLUME = 0.2;
    const FPS_ENEMY = { hpMin: 100, hpMax: 130, speed: 2.1, attackRange: 1.7, attackCd: 3, attackDamage: 10 };
    const FPS_BOSS = { hp: 10000, speed: FPS_ENEMY.speed, attackDamage: 75, spawnDelayMs: 90000, minionDelayMs: 10000, minionCount: 10, scale: 3 };
    const FPS_MAP = {
      size: 60,
      buildings: [
        { x: 10, z: 10, w: 6, d: 6 }, { x: -13, z: 8, w: 5, d: 5 }, { x: 5, z: -15, w: 7, d: 5 },
        { x: -9, z: -11, w: 6, d: 6 }, { x: 18, z: -4, w: 4, d: 6 }, { x: -17, z: 18, w: 5, d: 7 },
        { x: 0, z: 22, w: 6, d: 4 }
      ],
      covers: [
        { x: 3, z: 4, w: 3, d: 1 }, { x: -4, z: 1, w: 1.2, d: 3 }, { x: 12, z: -6, w: 2.4, d: 1 },
        { x: -8, z: -2, w: 3, d: 1 }, { x: 15, z: 12, w: 1, d: 3 }, { x: -14, z: -6, w: 2, d: 1.2 },
        { x: 2, z: -8, w: 3, d: 1 }, { x: -2, z: 12, w: 1.2, d: 3 }, { x: 8, z: 18, w: 3, d: 1 },
        { x: -18, z: -14, w: 3, d: 1 }, { x: 20, z: 8, w: 1, d: 3 }, { x: -6, z: 20, w: 2, d: 1.2 }
      ]
    };

    const FPS_STATE = {
      ready: false,
      renderer: null,
      scene: null,
      camera: null,
      gunGroup: null,
      gunBodyParts: [],
      defaultGunBodyParts: [],
      defaultGunMuzzle: null,
      gunDefaultVisual: null,
      gunVisual: null,
      gunVisualApply: null,
      gunModelKey: null,
      gunMuzzle: null,
      weaponIndex: 0,
      flashMesh: null,
      flashLife: 0,
      obstacles: [],
      obsMeshes: [],
      enemies: [],
      enemyMeshes: [],
      tracers: [],
      keys: {},
      mouseDown: false,
      player: null,
      gun: null,
      guns: [],
      spawnCount: 0,
      botSequence: 0,
      nextSpawnAt: 0,
      boss: null,
      nextBossAt: 0,
      score: 0,
      phase: "menu",
      rank: null,
      startedAt: 0,
      pendingLock: false,
      rafId: 0,
      lastTime: 0,
      running: false,
      damageFlashTimer: null,
      paused: false,
      historyTrapped: false,
      onPopState: null,
      devtoolsCheckTimer: 0,
      devtoolsKeyHandler: null,
      devtoolsRefreshTriggered: false,
      mp: {
        mode: "solo", // solo | host | member
        serverId: null,
        mapId: null,
        hostName: null,
        peers: new Map(),
        peerHitMeshes: [],
         broadcastTimer: 0,
         botSnapshotTimer: 0,
         botsEnabled: true,
        bossGeneration: 0,
        bossSyncTimer: 0,
        meleeCd: 0,
        meleeAnim: 0,
        dead: false,
        // 每次进入房间和复活都递增，用于拒绝延迟到达的旧命中/奖励消息。
        lifeGeneration: 0,
        respawnAt: 0,
        pickups: null,
        pickupTimer: 0,
        music: { audio: null, active: false, startAt: 0, clockOffsetMs: 0, errorShown: false }
      }
    };

    function fpsBest() {
      try { return Number(localStorage.getItem(FPS_BEST_KEY)) || 0; } catch (_) { return 0; }
    }

    function fpsSaveBest(score) {
      const best = Math.max(fpsBest(), score);
      try { localStorage.setItem(FPS_BEST_KEY, String(best)); } catch (_) { }
      return best;
    }

    function fpsDevtoolsLikelyOpen() {
      const widthGap = Math.max(0, window.outerWidth - window.innerWidth);
      const heightGap = Math.max(0, window.outerHeight - window.innerHeight);
      return widthGap > FPS_DEVTOOLS_SIZE_THRESHOLD || heightGap > FPS_DEVTOOLS_SIZE_THRESHOLD;
    }

    function fpsHasDevtoolsRefreshGuard() {
      try { return sessionStorage.getItem(FPS_DEVTOOLS_GUARD_KEY) === "1"; } catch (_) { return false; }
    }

    function fpsSetDevtoolsRefreshGuard(enabled) {
      try {
        if (enabled) sessionStorage.setItem(FPS_DEVTOOLS_GUARD_KEY, "1");
        else sessionStorage.removeItem(FPS_DEVTOOLS_GUARD_KEY);
      } catch (_) { }
    }

    function fpsHandleDevtoolsDetected() {
      if (FPS_STATE.devtoolsRefreshTriggered || fpsHasDevtoolsRefreshGuard()) return;
      FPS_STATE.devtoolsRefreshTriggered = true;
      fpsSetDevtoolsRefreshGuard(true);
      window.location.reload();
    }

    function fpsCheckDevtools() {
      if (fpsDevtoolsLikelyOpen()) {
        fpsHandleDevtoolsDetected();
      } else if (fpsHasDevtoolsRefreshGuard()) {
        // The post-refresh guard lasts while the tools remain open, preventing a reload loop.
        fpsSetDevtoolsRefreshGuard(false);
      }
    }

    function startFpsDevtoolsGuard() {
      if (FPS_STATE.devtoolsCheckTimer) return;
      FPS_STATE.devtoolsKeyHandler = (event) => {
        const key = String(event.key || "").toUpperCase();
        const opensDevtools = key === "F12"
          || ((event.ctrlKey || event.metaKey) && event.shiftKey && ["I", "J", "C"].includes(key));
        if (opensDevtools) fpsHandleDevtoolsDetected();
      };
      window.addEventListener("keydown", FPS_STATE.devtoolsKeyHandler, true);
      fpsCheckDevtools();
      FPS_STATE.devtoolsCheckTimer = window.setInterval(fpsCheckDevtools, FPS_DEVTOOLS_CHECK_INTERVAL);
    }

    function stopFpsDevtoolsGuard() {
      if (FPS_STATE.devtoolsCheckTimer) {
        window.clearInterval(FPS_STATE.devtoolsCheckTimer);
        FPS_STATE.devtoolsCheckTimer = 0;
      }
      if (FPS_STATE.devtoolsKeyHandler) {
        window.removeEventListener("keydown", FPS_STATE.devtoolsKeyHandler, true);
        FPS_STATE.devtoolsKeyHandler = null;
      }
      FPS_STATE.devtoolsRefreshTriggered = false;
    }

    // 每把武器独立保存弹匣与备弹，切换时只变更当前引用。
    function fpsResetWeaponLoadout() {
      if (!WEAPONS.length) {
        FPS_STATE.guns = [];
        FPS_STATE.gun = null;
        return null;
      }
      if (!Number.isInteger(FPS_STATE.weaponIndex)) FPS_STATE.weaponIndex = 0;
      FPS_STATE.weaponIndex = ((FPS_STATE.weaponIndex % WEAPONS.length) + WEAPONS.length) % WEAPONS.length;
      FPS_STATE.guns = WEAPONS.map((weapon) => ({
        mag: weapon.magSize,
        reserve: weapon.reserve,
        fireCd: 0,
        reloading: false,
        reloadLeft: 0,
        config: weapon
      }));
      FPS_STATE.gun = FPS_STATE.guns[FPS_STATE.weaponIndex];
      return FPS_STATE.gun;
    }

    function mpAdvanceLifeGeneration() {
      const previous = FPS_STATE.mp.lifeGeneration;
      FPS_STATE.mp.lifeGeneration = Number.isSafeInteger(previous) && previous >= 0 && previous < Number.MAX_SAFE_INTEGER
        ? previous + 1
        : 1;
    }

    function fpsEnsureMpMusic() {
      const music = FPS_STATE.mp.music;
      if (music.audio) return music.audio;
      const audio = new Audio(FPS_MP_MUSIC_PATH);
      audio.loop = true;
      audio.preload = "auto";
      audio.muted = true;
      audio.volume = 0;
      audio.addEventListener("loadedmetadata", fpsSyncMpMusic);
      audio.addEventListener("canplay", fpsSyncMpMusic);
      audio.addEventListener("error", () => {
        if (!music.active || music.errorShown) return;
        music.errorShown = true;
        showToast("多人背景音乐加载失败", "error", 2500);
      });
      music.audio = audio;
      return audio;
    }

    // 创建或加入按钮属于用户手势，先静音启动以满足浏览器的媒体播放限制。
    function fpsPrepareMpMusic() {
      const audio = fpsEnsureMpMusic();
      audio.muted = true;
      audio.volume = 0;
      const play = audio.play();
      if (play && play.catch) play.catch(() => { });
    }

    function fpsStartMpMusic(startAt, serverNow) {
      if (!Number.isFinite(startAt) || startAt <= 0) return;
      const music = FPS_STATE.mp.music;
      music.startAt = startAt;
      music.clockOffsetMs = Number.isFinite(serverNow) && serverNow > 0 ? serverNow - Date.now() : 0;
      music.active = true;
      music.errorShown = false;
      fpsSyncMpMusic();
    }

    function fpsSyncMpMusic() {
      const music = FPS_STATE.mp.music;
      if (!music.active || !music.startAt) return;
      const audio = fpsEnsureMpMusic();
      const duration = audio.duration;
      if (!Number.isFinite(duration) || duration <= 0) return;
      const elapsed = Math.max(0, (Date.now() + music.clockOffsetMs - music.startAt) / 1000);
      const targetTime = elapsed % duration;
      if (audio.paused || Math.abs(audio.currentTime - targetTime) > 0.35) {
        try { audio.currentTime = targetTime; } catch (_) { }
      }
      audio.muted = false;
      audio.volume = FPS_MP_MUSIC_VOLUME;
      const play = audio.play();
      if (play && play.catch) play.catch(() => { });
    }

    function fpsStopMpMusic() {
      const music = FPS_STATE.mp.music;
      music.active = false;
      music.startAt = 0;
      music.clockOffsetMs = 0;
      if (!music.audio) return;
      music.audio.pause();
      music.audio.muted = true;
      music.audio.volume = 0;
      try { music.audio.currentTime = 0; } catch (_) { }
    }

    function initFpsScene() {
      if (typeof THREE === "undefined") {
        ui.fpsStage.replaceChildren();
        const d = document.createElement("div");
        d.className = "earth-stage-missing";
        d.textContent = "3D 引擎未加载";
        ui.fpsStage.appendChild(d);
        return;
      }
      if (FPS_STATE.ready) return;
      let renderer;
      try {
        renderer = new THREE.WebGLRenderer({ antialias: true });
      } catch (_) {
        ui.fpsStage.replaceChildren();
        const d = document.createElement("div");
        d.className = "earth-stage-missing";
        d.textContent = "当前浏览器不支持 WebGL";
        ui.fpsStage.appendChild(d);
        return;
      }
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      ui.fpsStage.appendChild(renderer.domElement);
      const scene = new THREE.Scene();
      scene.background = new THREE.Color(0x87b5d9);
      scene.fog = new THREE.Fog(0x87b5d9, 45, 95);
      const camera = new THREE.PerspectiveCamera(75, 1, 0.05, 200);
      camera.rotation.order = "YXZ";
      scene.add(camera);
      scene.add(new THREE.AmbientLight(0x9090a0, 0.75));
      const sun = new THREE.DirectionalLight(0xffffff, 0.9);
      sun.position.set(20, 30, 10);
      scene.add(sun);

      const groundCanvas = document.createElement("canvas");
      groundCanvas.width = groundCanvas.height = 512;
      const gc = groundCanvas.getContext("2d");
      gc.fillStyle = "#3d4a41";
      gc.fillRect(0, 0, 512, 512);
      gc.strokeStyle = "rgba(0,0,0,.25)";
      gc.lineWidth = 2;
      for (let i = 0; i <= 16; i++) {
        gc.beginPath(); gc.moveTo(i * 32, 0); gc.lineTo(i * 32, 512); gc.stroke();
        gc.beginPath(); gc.moveTo(0, i * 32); gc.lineTo(512, i * 32); gc.stroke();
      }
      const groundTex = new THREE.CanvasTexture(groundCanvas);
      groundTex.repeat.set(16, 16);
      groundTex.wrapS = groundTex.wrapT = THREE.RepeatWrapping;
      const ground = new THREE.Mesh(new THREE.PlaneGeometry(FPS_MAP.size, FPS_MAP.size),
        new THREE.MeshLambertMaterial({ map: groundTex }));
      ground.rotation.x = -Math.PI / 2;
      scene.add(ground);

      const wallMat = new THREE.MeshLambertMaterial({ color: 0x6a7580 });
      const mkWall = (x, z, w, d) => {
        const wall = new THREE.Mesh(new THREE.BoxGeometry(w, 3, d), wallMat);
        wall.position.set(x, 1.5, z);
        scene.add(wall);
        FPS_STATE.obsMeshes.push(wall);
      };
      mkWall(0, -30, FPS_MAP.size, 1);
      mkWall(0, 30, FPS_MAP.size, 1);
      mkWall(-30, 0, 1, FPS_MAP.size);
      mkWall(30, 0, 1, FPS_MAP.size);

      const obstacles = [];
      FPS_MAP.buildings.forEach((b) => {
        const mesh = new THREE.Mesh(new THREE.BoxGeometry(b.w, 5, b.d), new THREE.MeshLambertMaterial({ color: 0x9a8f7a }));
        mesh.position.set(b.x, 2.5, b.z);
        scene.add(mesh);
        FPS_STATE.obsMeshes.push(mesh);
        obstacles.push({ minX: b.x - b.w / 2, maxX: b.x + b.w / 2, minY: 0, maxY: 5, minZ: b.z - b.d / 2, maxZ: b.z + b.d / 2, standable: false });
      });
      FPS_MAP.covers.forEach((c) => {
        const mesh = new THREE.Mesh(new THREE.BoxGeometry(c.w, 1, c.d), new THREE.MeshLambertMaterial({ color: 0x8a8f96 }));
        mesh.position.set(c.x, 0.5, c.z);
        scene.add(mesh);
        FPS_STATE.obsMeshes.push(mesh);
        obstacles.push({ minX: c.x - c.w / 2, maxX: c.x + c.w / 2, minY: 0, maxY: 1, minZ: c.z - c.d / 2, maxZ: c.z + c.d / 2, standable: true });
      });
      obstacles.push({ minX: -30, maxX: 30, minY: 0, maxY: 3, minZ: -30.5, maxZ: -29.5, standable: false });
      obstacles.push({ minX: -30, maxX: 30, minY: 0, maxY: 3, minZ: 29.5, maxZ: 30.5, standable: false });
      obstacles.push({ minX: -30.5, maxX: -29.5, minY: 0, maxY: 3, minZ: -30, maxZ: 30, standable: false });
      obstacles.push({ minX: 29.5, maxX: 30.5, minY: 0, maxY: 3, minZ: -30, maxZ: 30, standable: false });
      FPS_STATE.obstacles = obstacles;

      const gun = new THREE.Group();
      const gmat = new THREE.MeshLambertMaterial({ color: 0x2b2f33 });
      const barrel = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.06, 0.55), gmat);
      barrel.position.set(0, 0.02, -0.42);
      const gunBody = new THREE.Mesh(new THREE.BoxGeometry(0.09, 0.14, 0.4), gmat);
      gunBody.position.set(0, -0.02, -0.2);
      const grip = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.16, 0.09), gmat);
      grip.position.set(0, -0.13, -0.06);
      const mag = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.18, 0.08), new THREE.MeshLambertMaterial({ color: 0x1c1f22 }));
      mag.position.set(0, -0.14, -0.12);
      const muzzle = new THREE.Mesh(new THREE.SphereGeometry(0.035, 6, 6), new THREE.MeshBasicMaterial({ color: 0x202428 }));
      muzzle.position.set(0, 0.02, -0.72);
      const defaultVisual = new THREE.Group();
      defaultVisual.add(barrel, gunBody, grip, mag, muzzle);
      gun.add(defaultVisual);
      gun.position.set(0.3, -0.28, -0.55);
      camera.add(gun);
      FPS_STATE.gunGroup = gun;
      FPS_STATE.gunDefaultVisual = defaultVisual;
      FPS_STATE.defaultGunBodyParts = [barrel, gunBody, grip];
      FPS_STATE.defaultGunMuzzle = muzzle;
      FPS_STATE.gunBodyParts = [barrel, gunBody, grip];
      FPS_STATE.gunMuzzle = muzzle;
      const flash = new THREE.Mesh(
        new THREE.PlaneGeometry(0.2, 0.2),
        new THREE.MeshBasicMaterial({ color: 0xffd27a, transparent: true, opacity: 0.9, side: THREE.DoubleSide })
      );
      flash.position.set(0, 0.02, -0.8);
      flash.visible = false;
      gun.add(flash);
      FPS_STATE.flashMesh = flash;
      fpsApplyWeaponAppearance(WEAPONS[FPS_STATE.weaponIndex]);

      FPS_STATE.renderer = renderer;
      FPS_STATE.scene = scene;
      FPS_STATE.camera = camera;
      FPS_STATE.ready = true;
      resizeFps();
      window.addEventListener("resize", resizeFps);
      bindFpsInput();
      bindFpsTouchButtons();
    }

    function fpsSetGunModel(weapon) {
      const factory = weapon && weapon.model && window.FPS_WEAPON_MODELS
        ? window.FPS_WEAPON_MODELS[weapon.model] : null;
      if (!factory) {
        if (FPS_STATE.gunDefaultVisual) FPS_STATE.gunDefaultVisual.visible = true;
        if (FPS_STATE.gunVisual) FPS_STATE.gunGroup.remove(FPS_STATE.gunVisual);
        FPS_STATE.gunVisual = null;
        FPS_STATE.gunVisualApply = null;
        FPS_STATE.gunModelKey = null;
        FPS_STATE.gunBodyParts = FPS_STATE.defaultGunBodyParts;
        FPS_STATE.gunMuzzle = FPS_STATE.defaultGunMuzzle;
        if (FPS_STATE.flashMesh) FPS_STATE.flashMesh.position.set(0, 0.02, -0.8);
        return;
      }
      if (FPS_STATE.gunDefaultVisual) FPS_STATE.gunDefaultVisual.visible = false;
      if (FPS_STATE.gunModelKey !== weapon.model || !FPS_STATE.gunVisual) {
        if (FPS_STATE.gunVisual) FPS_STATE.gunGroup.remove(FPS_STATE.gunVisual);
        const visual = factory.create(THREE, weapon);
        FPS_STATE.gunVisual = visual.group;
        FPS_STATE.gunVisualApply = typeof visual.applyAppearance === "function" ? visual.applyAppearance : null;
        FPS_STATE.gunModelKey = weapon.model;
        FPS_STATE.gunBodyParts = visual.bodyParts || [];
        FPS_STATE.gunMuzzle = visual.muzzle || null;
        FPS_STATE.gunGroup.add(visual.group);
        if (FPS_STATE.flashMesh && visual.flashPosition) FPS_STATE.flashMesh.position.copy(visual.flashPosition);
      }
    }

    function fpsApplyWeaponAppearance(weapon) {
      if (!weapon) return;
      fpsSetGunModel(weapon);
      if (FPS_STATE.gunVisualApply) FPS_STATE.gunVisualApply(weapon);
      FPS_STATE.gunBodyParts.forEach((part) => {
        if (part.material && part.material.color) part.material.color.setHex(weapon.color);
      });
      if (FPS_STATE.gunMuzzle && FPS_STATE.gunMuzzle.material && FPS_STATE.gunMuzzle.material.color) {
        FPS_STATE.gunMuzzle.material.color.setHex(weapon.muzzleColor);
      }
    }

    // 触屏虚拟按键：WASD 移动、跳、发射（按住连发）、F 下蹲、Z 循环切枪、G/X 直达新武器、J 开关机器人、✕ 退出
    function bindFpsTouchButtons() {
      const bind = (id, onStart, onEnd) => {
        const el = document.getElementById(id);
        if (!el) return;
        const start = (e) => { e.preventDefault(); onStart(); };
        const end = (e) => { e.preventDefault(); onEnd(); };
        el.addEventListener("touchstart", start, { passive: false });
        el.addEventListener("touchend", end, { passive: false });
        el.addEventListener("touchcancel", end, { passive: false });
        el.addEventListener("mousedown", start);
        el.addEventListener("mouseup", end);
        el.addEventListener("mouseleave", end);
      };
      bind("tBtnW", () => { FPS_STATE.keys["KeyW"] = true; }, () => { FPS_STATE.keys["KeyW"] = false; });
      bind("tBtnA", () => { FPS_STATE.keys["KeyA"] = true; }, () => { FPS_STATE.keys["KeyA"] = false; });
      bind("tBtnS", () => { FPS_STATE.keys["KeyS"] = true; }, () => { FPS_STATE.keys["KeyS"] = false; });
      bind("tBtnD", () => { FPS_STATE.keys["KeyD"] = true; }, () => { FPS_STATE.keys["KeyD"] = false; });
      bind("tBtnSpace", () => { FPS_STATE.keys["Space"] = true; }, () => { FPS_STATE.keys["Space"] = false; });
      bind("tBtnFire", () => { FPS_STATE.mouseDown = true; }, () => { FPS_STATE.mouseDown = false; });
      bind("tBtnF", () => fpsToggleCrouch(), () => { });
      bind("tBtnZ", () => switchWeapon(), () => { });
      bind("tBtnG", () => selectWeaponById("m249"), () => { });
      bind("tBtnX", () => selectWeaponById("ddr600"), () => { });
      bind("tBtnJ", () => {
        if (FPS_STATE.mp.mode === "host") sendProtocol("/mp_bots|" + (FPS_STATE.mp.botsEnabled ? "off" : "on"));
      }, () => { });
      bind("tBtnExit", () => {
        if (FPS_STATE.mp.mode !== "solo") mpExitGame(true);
        else fpsSettle();
      }, () => { });
    }

    function resizeFps() {
      if (!FPS_STATE.ready) return;
      const stage = ui.fpsStage;
      const w = Math.max(1, stage.clientWidth);
      const h = Math.max(1, stage.clientHeight);
      FPS_STATE.renderer.setSize(w, h, false);
      FPS_STATE.camera.aspect = w / h;
      FPS_STATE.camera.updateProjectionMatrix();
    }

    function bindFpsInput() {
      const canvas = FPS_STATE.renderer.domElement;
      canvas.addEventListener("contextmenu", (e) => e.preventDefault());
      document.addEventListener("keydown", (e) => {
        if (e.code === "Escape" && FPS_STATE.paused) {
          e.preventDefault();
          fpsPauseExit();
          return;
        }
        FPS_STATE.keys[e.code] = true;
        if (FPS_STATE.phase === "playing") {
          if (e.code === "KeyR") fpsReload();
          else if (e.code === "KeyF") fpsToggleCrouch();
          else if (e.code === "KeyC") fpsMelee();
          else if (e.code === "KeyZ") {
            e.preventDefault();
            if (!e.repeat) switchWeapon();
          }
          else if (e.code === "KeyG") {
            e.preventDefault();
            if (!e.repeat) selectWeaponById("m249");
          }
          else if (e.code === "KeyX") {
            e.preventDefault();
            if (!e.repeat) selectWeaponById("ddr600");
          }
          else if (e.code === "KeyJ" && FPS_STATE.mp.mode === "host") {
            sendProtocol("/mp_bots|" + (FPS_STATE.mp.botsEnabled ? "off" : "on"));
          }
          else if (e.code === "Space") e.preventDefault();
        }
      });
      document.addEventListener("keyup", (e) => { FPS_STATE.keys[e.code] = false; });
      if (!IS_TOUCH_DEVICE) {
        document.addEventListener("mousedown", (e) => {
          if (FPS_STATE.phase === "playing" && e.button === 0) FPS_STATE.mouseDown = true;
        });
        document.addEventListener("mouseup", (e) => {
          if (e.button === 0) FPS_STATE.mouseDown = false;
        });
        document.addEventListener("mousemove", (e) => {
          if (FPS_STATE.phase !== "playing" || !FPS_STATE.player) return;
          const sens = 0.0022;
          FPS_STATE.player.yaw -= e.movementX * sens;
          FPS_STATE.player.pitch -= e.movementY * sens;
          FPS_STATE.player.pitch = Math.max(-1.55, Math.min(1.55, FPS_STATE.player.pitch));
        });
      } else {
        // 触屏瞄准：单指拖动旋转视角
        let lookTouch = null;
        canvas.addEventListener("touchstart", (e) => {
          if (lookTouch === null && e.touches.length > 0) {
            const t = e.touches[0];
            lookTouch = { id: t.identifier, x: t.clientX, y: t.clientY };
          }
        }, { passive: true });
        canvas.addEventListener("touchmove", (e) => {
          if (FPS_STATE.phase !== "playing" || !FPS_STATE.player || !lookTouch) return;
          for (const t of e.changedTouches) {
            if (t.identifier === lookTouch.id) {
              const sens = 0.0042;
              FPS_STATE.player.yaw -= (t.clientX - lookTouch.x) * sens;
              FPS_STATE.player.pitch -= (t.clientY - lookTouch.y) * sens;
              FPS_STATE.player.pitch = Math.max(-1.55, Math.min(1.55, FPS_STATE.player.pitch));
              lookTouch.x = t.clientX;
              lookTouch.y = t.clientY;
            }
          }
        }, { passive: true });
        canvas.addEventListener("touchend", (e) => {
          for (const t of e.changedTouches) {
            if (lookTouch && t.identifier === lookTouch.id) lookTouch = null;
          }
        }, { passive: true });
        canvas.addEventListener("touchcancel", () => { lookTouch = null; }, { passive: true });
      }
      document.addEventListener("pointerlockchange", () => {
        if (!IS_TOUCH_DEVICE && FPS_STATE.phase === "playing" && !document.pointerLockElement) {
          fpsShowPause(); // 指针锁定意外中断（误触手势等）→ 暂停而非直接退出
        }
      });
      document.addEventListener("fullscreenchange", () => {
        if (!IS_TOUCH_DEVICE && FPS_STATE.phase === "playing" && !document.fullscreenElement) {
          fpsShowPause();
        }
        else if (FPS_STATE.pendingLock && document.fullscreenElement) {
          FPS_STATE.pendingLock = false;
          try { FPS_STATE.renderer.domElement.requestPointerLock(); } catch (_) { }
        }
      });
      document.addEventListener("pointerlockerror", () => {
        if (FPS_STATE.phase === "playing" && Date.now() - FPS_STATE.startedAt < 2000) {
          showToast("未能锁定鼠标：点击游戏画面可重试", "info", 3000);
          const canvas = FPS_STATE.renderer.domElement;
          const retry = () => {
            try { canvas.requestPointerLock(); } catch (_) { }
            canvas.removeEventListener("click", retry);
          };
          canvas.addEventListener("click", retry);
        }
      });
    }

    function setFpsTouchControls(show) {
      if (ui.fpsTouchControls) ui.fpsTouchControls.hidden = !show;
    }

    // 进入游戏：触屏设备显示虚拟按键并跳过指针锁定（触屏瞄准）；桌面设备全屏 + 指针锁定
    function fpsRequestLockScreen() {
      if (IS_TOUCH_DEVICE) {
        setFpsTouchControls(true);
        if (document.fullscreenEnabled && !document.fullscreenElement) {
          const p = (document.documentElement.requestFullscreen || function () { }).call(document.documentElement);
          if (p && p.catch) p.catch(() => { });
        }
        return;
      }
      const canvas = FPS_STATE.renderer.domElement;
      if (document.fullscreenEnabled && !document.fullscreenElement) {
        FPS_STATE.pendingLock = true;
        const p = (document.documentElement.requestFullscreen || function () { }).call(document.documentElement);
        if (p && p.catch) p.catch(() => { });
      } else {
        try { canvas.requestPointerLock(); } catch (_) { }
      }
    }

    function fpsStart() {
      if (!FPS_STATE.ready) return;
      FPS_STATE.player = {
        x: 0, z: 8, y: 0, prevY: 0, vy: 0, onGround: true, yaw: 0, pitch: 0,
        crouched: false, hp: FPS_PLAYER.hp
      };
      const g = fpsResetWeaponLoadout();
      const w = g.config;
      FPS_STATE.spawnCount = 0;
      // 单人第一只普通机器人按新的 1~10 只节奏在 8 秒后出现；多人保持原来的 12 秒。
      FPS_STATE.nextSpawnAt = performance.now() + (FPS_STATE.mp.mode === "solo" ? 8000 : 12000);
      FPS_STATE.boss = null;
      FPS_STATE.nextBossAt = performance.now() + FPS_BOSS.spawnDelayMs;
      FPS_STATE.score = 0;
      FPS_STATE.rank = null;
      FPS_STATE.enemies.forEach((e) => FPS_STATE.scene.remove(e.group));
      FPS_STATE.enemies = [];
      FPS_STATE.enemyMeshes = [];
      FPS_STATE.tracers.forEach((t) => FPS_STATE.scene.remove(t.line));
      FPS_STATE.tracers = [];
      FPS_STATE.phase = "playing";
      FPS_STATE.startedAt = Date.now();
      ui.fpsMenu.hidden = true;
      ui.fpsSettle.hidden = true;
      ui.fpsHud.hidden = false;
      ui.fpsDamageFlash.hidden = true;
      ui.fpsScoreWrap.hidden = false;
      ui.fpsSelfId.hidden = true;
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsMpInfo.textContent = "";
      ui.fpsWeaponInfo.textContent = w.name;
      fpsApplyWeaponAppearance(w);
      updateFpsHud();
      if (ui.tBtnJ) ui.tBtnJ.disabled = true; // 单人模式 J 不可用
      fpsTrapHistory();
      fpsRequestLockScreen();
    }

    function fpsAbort() {
      fpsStopMpMusic();
      if (FPS_STATE.mp.mode !== "solo") {
        sendProtocol("/mp_leave");
        FPS_STATE.mp.mode = "solo";
        FPS_STATE.mp.serverId = null;
        FPS_STATE.mp.peers.forEach((peer) => removePeer(peer.name));
        FPS_STATE.mp.peers.clear();
        FPS_STATE.mp.peerHitMeshes = [];
        FPS_STATE.enemies.forEach((e) => FPS_STATE.scene.remove(e.group));
        FPS_STATE.enemies = [];
        FPS_STATE.enemyMeshes = [];
        FPS_STATE.boss = null;
        FPS_STATE.nextBossAt = 0;
        ui.fpsScoreWrap.hidden = false;
        ui.fpsSelfId.hidden = true;
      }
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsPause.hidden = true;
      FPS_STATE.paused = false;
      fpsUntrapHistory();
      if (document.exitFullscreen && document.fullscreenElement) document.exitFullscreen().catch(() => { });
      if (document.exitPointerLock && document.pointerLockElement) document.exitPointerLock();
      setFpsTouchControls(false);
      FPS_STATE.phase = "menu";
      ui.fpsHud.hidden = true;
      ui.fpsSettle.hidden = true;
      ui.fpsMenu.hidden = false;
    }

    function fpsToggleCrouch() {
      if (FPS_STATE.player) FPS_STATE.player.crouched = !FPS_STATE.player.crouched;
    }

    function fpsReload() {
      const g = FPS_STATE.gun;
      if (!g || !g.config || g.reloading || g.mag >= g.config.magSize || g.reserve <= 0) return;
      g.reloading = true;
      g.reloadLeft = FPS_RELOAD_TIME;
    }

    function switchWeapon() {
      if (FPS_STATE.phase !== "playing" || !FPS_STATE.gun) return;
      if (FPS_STATE.guns.length !== WEAPONS.length) fpsResetWeaponLoadout();
      fpsSelectWeapon((FPS_STATE.weaponIndex + 1) % WEAPONS.length);
    }

    function selectWeaponById(id) {
      if (FPS_STATE.phase !== "playing" || !FPS_STATE.gun) return;
      const weaponIndex = WEAPONS.findIndex((weapon) => weapon.id === id);
      if (weaponIndex >= 0) fpsSelectWeapon(weaponIndex);
    }

    function fpsSelectWeapon(weaponIndex) {
      if (!Number.isInteger(weaponIndex) || weaponIndex < 0 || weaponIndex >= WEAPONS.length) return;
      if (FPS_STATE.guns.length !== WEAPONS.length) fpsResetWeaponLoadout();
      if (weaponIndex === FPS_STATE.weaponIndex) return;
      const previous = FPS_STATE.gun;
      previous.reloading = false;
      previous.reloadLeft = 0;
      previous.fireCd = 0;
      FPS_STATE.weaponIndex = weaponIndex;
      const g = FPS_STATE.guns[FPS_STATE.weaponIndex];
      FPS_STATE.gun = g;
      const w = g.config;
      g.reloading = false;
      g.reloadLeft = 0;
      g.fireCd = 0;
      fpsApplyWeaponAppearance(w);
      updateFpsHud();
      showToast("切换至 " + w.name, "info", 1200);
    }

    function fpsPlayerAABB(p) {
      const h = p.crouched ? FPS_PLAYER.heightCrouch : FPS_PLAYER.heightStand;
      const r = FPS_PLAYER.radius;
      return { minX: p.x - r, maxX: p.x + r, minY: p.y, maxY: p.y + h, minZ: p.z - r, maxZ: p.z + r };
    }

    function fpsOverlaps(a, b) {
      return a.minX < b.maxX && a.maxX > b.minX && a.minY < b.maxY && a.maxY > b.minY && a.minZ < b.maxZ && a.maxZ > b.minZ;
    }

    function fpsResolveX(p) {
      const box = fpsPlayerAABB(p);
      for (const o of FPS_STATE.obstacles) {
        if (fpsOverlaps(box, o)) {
          p.x = p.x < (o.minX + o.maxX) / 2 ? o.minX - FPS_PLAYER.radius : o.maxX + FPS_PLAYER.radius;
          box.minX = p.x - FPS_PLAYER.radius;
          box.maxX = p.x + FPS_PLAYER.radius;
        }
      }
    }

    function fpsResolveZ(p) {
      const box = fpsPlayerAABB(p);
      for (const o of FPS_STATE.obstacles) {
        if (fpsOverlaps(box, o)) {
          p.z = p.z < (o.minZ + o.maxZ) / 2 ? o.minZ - FPS_PLAYER.radius : o.maxZ + FPS_PLAYER.radius;
          box.minZ = p.z - FPS_PLAYER.radius;
          box.maxZ = p.z + FPS_PLAYER.radius;
        }
      }
    }

    function fpsResolveY(p) {
      let grounded = false;
      const r = FPS_PLAYER.radius;
      for (const o of FPS_STATE.obstacles) {
        if (!o.standable) continue;
        const hOverlap = p.x + r > o.minX && p.x - r < o.maxX && p.z + r > o.minZ && p.z - r < o.maxZ;
        if (!hOverlap) continue;
        if (p.prevY >= o.maxY - 0.001 && p.y <= o.maxY) {
          p.y = o.maxY;
          p.vy = 0;
          grounded = true;
        }
      }
      if (p.y <= 0) {
        p.y = 0;
        p.vy = Math.max(0, p.vy);
        grounded = true;
      }
      p.onGround = grounded;
      p.prevY = p.y;
    }

    function updateFpsPlayer(dt) {
      const p = FPS_STATE.player;
      if (FPS_STATE.mp.mode !== "solo" && FPS_STATE.mp.dead) {
        FPS_STATE.camera.position.set(p.x, p.y + (p.crouched ? FPS_PLAYER.eyeCrouch : FPS_PLAYER.eyeStand), p.z);
        FPS_STATE.camera.rotation.set(p.pitch, p.yaw, 0);
        return;
      }
      const keys = FPS_STATE.keys;
      let fwd = 0, strafe = 0;
      if (keys["KeyW"]) fwd += 1;
      if (keys["KeyS"]) fwd -= 1;
      if (keys["KeyA"]) strafe -= 1;
      if (keys["KeyD"]) strafe += 1;
      const weapon = FPS_STATE.gun && FPS_STATE.gun.config;
      const speedMultiplier = weapon && Number.isFinite(weapon.speedMultiplier) ? weapon.speedMultiplier : 1;
      const speed = (p.crouched ? FPS_PLAYER.crouchSpeed : FPS_PLAYER.speed) * speedMultiplier;
      const sin = Math.sin(p.yaw), cos = Math.cos(p.yaw);
      const dx = (-sin * fwd + cos * strafe) * speed * dt;
      const dz = (-cos * fwd - sin * strafe) * speed * dt;
      p.x += dx;
      fpsResolveX(p);
      p.z += dz;
      fpsResolveZ(p);
      if (keys["Space"] && p.onGround) {
        p.vy = FPS_PLAYER.jumpVel;
        p.onGround = false;
      }
      p.vy -= FPS_PLAYER.gravity * dt;
      p.y += p.vy * dt;
      fpsResolveY(p);
      FPS_STATE.camera.position.set(p.x, p.y + (p.crouched ? FPS_PLAYER.eyeCrouch : FPS_PLAYER.eyeStand), p.z);
      FPS_STATE.camera.rotation.set(p.pitch, p.yaw, 0);
    }

    function fpsTryFire(dt) {
      const g = FPS_STATE.gun;
      g.fireCd -= dt;
      // 多人复活倒计时期间不能攻击，避免死亡玩家继续开火。
      if (FPS_STATE.mp.mode !== "solo" && FPS_STATE.mp.dead) {
        FPS_STATE.mouseDown = false;
        return;
      }
      if (!FPS_STATE.mouseDown || g.fireCd > 0 || g.reloading) return;
      if (g.mag <= 0) { fpsReload(); return; }
      g.fireCd = 1 / g.config.fireRate; // 冷却单位与 dt 一致（秒）
      g.mag--;
      // 随机后坐力：视角轻微随机偏转
      FPS_STATE.player.pitch += 0.004 + Math.random() * 0.007;
      FPS_STATE.player.yaw += (Math.random() - 0.5) * 0.012;
      FPS_STATE.player.pitch = Math.max(-1.55, Math.min(1.55, FPS_STATE.player.pitch));
      fpsShootRay();
      FPS_STATE.flashMesh.visible = true;
      FPS_STATE.flashMesh.rotation.z = Math.random() * Math.PI * 2;
      FPS_STATE.flashLife = 0.05;
      updateFpsHud();
    }

    function fpsShootRay() {
      if (FPS_STATE.mp.mode !== "solo" && FPS_STATE.mp.dead) return;
      const cam = FPS_STATE.camera;
      const dir = new THREE.Vector3(0, 0, -1).applyQuaternion(cam.quaternion);
      const origin = new THREE.Vector3().copy(cam.position);
      const ray = new THREE.Raycaster(origin, dir, 0.05, 200);
      let hitEnemy = null;
      if (FPS_STATE.enemyMeshes.length) hitEnemy = ray.intersectObjects(FPS_STATE.enemyMeshes, false)[0] || null;
      let hitPeer = null;
      const livingPeerMeshes = FPS_STATE.mp.peerHitMeshes.filter((mesh) => {
        const peerInfo = mesh.userData.peer;
        const peer = peerInfo && FPS_STATE.mp.peers.get(peerInfo.name);
        return peer && peer.alive;
      });
      if (livingPeerMeshes.length) hitPeer = ray.intersectObjects(livingPeerMeshes, false)[0] || null;
      const hitObs = ray.intersectObjects(FPS_STATE.obsMeshes, false)[0];
      const enemies = [];
      if (hitEnemy) enemies.push({ d: hitEnemy.distance, target: hitEnemy.object.userData.enemy, point: hitEnemy.point, part: hitEnemy.object.userData.part });
      if (hitPeer) enemies.push({ d: hitPeer.distance, peer: hitPeer.object.userData.peer, point: hitPeer.point, part: hitPeer.object.userData.part });
      let nearest = null;
      for (const e of enemies) {
        if (!nearest || e.d < nearest.d) nearest = e;
      }
      let hitPoint = null;
      if (nearest && (!hitObs || nearest.d < hitObs.distance)) {
        hitPoint = nearest.point.clone();
        if (nearest.peer) {
          const w = FPS_STATE.gun.config;
          const dmg = nearest.part === "head" ? w.damageHead : w.damageBody;
          const peer = FPS_STATE.mp.peers.get(nearest.peer.name);
          const weaponIndex = FPS_STATE.weaponIndex;
          if (peer && peer.alive && Number.isSafeInteger(peer.lifeGeneration) && peer.lifeGeneration > 0
            && Number.isSafeInteger(FPS_STATE.mp.lifeGeneration) && FPS_STATE.mp.lifeGeneration > 0
            && Number.isInteger(weaponIndex) && weaponIndex >= 0 && weaponIndex < FPS_STATE.guns.length) {
            sendProtocol("/mp_hit|" + peer.name + "|" + dmg + "|" + weaponIndex + "|"
              + FPS_STATE.mp.lifeGeneration + "|" + peer.lifeGeneration);
          }
        } else if (nearest.target) {
          const damage = nearest.part === "head" ? FPS_STATE.gun.config.damageHead : FPS_STATE.gun.config.damageBody;
          if (nearest.target.isBoss && FPS_STATE.mp.mode !== "solo") {
            sendProtocol("/mp_boss_hit|" + nearest.target.generation + "|" + damage + "|"
              + FPS_STATE.weaponIndex + "|" + FPS_STATE.mp.lifeGeneration);
          } else if (FPS_STATE.mp.mode !== "solo" && nearest.target.botId) {
            // 普通机器人由房主权威扣血，所有客户端只发送命中请求。
            if (FPS_STATE.mp.mode === "host") {
              fpsApplyMpBotHitRequest(state.nickname, nearest.target.botId, damage,
                FPS_STATE.weaponIndex, FPS_STATE.mp.lifeGeneration);
            } else {
              sendProtocol("/mp_bot_hit|" + encodeBase64Utf8(nearest.target.botId) + "|" + damage + "|"
                + FPS_STATE.weaponIndex + "|" + FPS_STATE.mp.lifeGeneration);
            }
          } else {
            nearest.target.hp -= damage;
            nearest.target.lastWeaponIndex = FPS_STATE.weaponIndex;
            nearest.target.flashTimer = 0.09;
            if (nearest.target.bodyMat) nearest.target.bodyMat.color.setHex(0xff9a9a);
            if (nearest.target.hp <= 0) fpsKillEnemy(nearest.target);
          }
        }
      } else if (hitObs) {
        hitPoint = hitObs.point.clone();
      }
      if (hitPoint) fpsAddTracer(origin, hitPoint);
    }

    function fpsAddTracer(from, to) {
      const geo = new THREE.BufferGeometry().setFromPoints([from, to]);
      const line = new THREE.Line(geo, new THREE.LineBasicMaterial({ color: 0xffd27a, transparent: true, opacity: 0.85 }));
      FPS_STATE.scene.add(line);
      FPS_STATE.tracers.push({ line, life: 0.07, maxLife: 0.07 });
    }

    function removeFpsEnemy(enemy) {
      FPS_STATE.scene.remove(enemy.group);
      const idx = FPS_STATE.enemies.indexOf(enemy);
      if (idx >= 0) FPS_STATE.enemies.splice(idx, 1);
      enemy.hitMeshes.forEach((m) => {
        const mi = FPS_STATE.enemyMeshes.indexOf(m);
        if (mi >= 0) FPS_STATE.enemyMeshes.splice(mi, 1);
      });
    }

    function fpsKillEnemy(enemy) {
      removeFpsEnemy(enemy);
      FPS_STATE.score += 1;
      if (enemy.isBoss && FPS_STATE.boss === enemy) {
        FPS_STATE.boss = null;
        FPS_STATE.nextBossAt = performance.now() + FPS_BOSS.spawnDelayMs;
        showToast("🏆 红色机器人 Boss 已被击败，90 秒后再次出现", "success", 3200);
      }
      const weaponIndex = enemy.lastWeaponIndex;
      const gun = Number.isInteger(weaponIndex) ? FPS_STATE.guns[weaponIndex] : null;
      const reward = gun && gun.config && Number.isSafeInteger(gun.config.botKillReserve) ? gun.config.botKillReserve : 0;
      if (reward > 0) {
        gun.reserve += reward;
        showToast("☠ 击杀机器人，" + gun.config.name + " 备弹 +" + reward, "success", 1800);
      }
      fpsSparks(enemy.pos);
      updateFpsHud();
    }

    function fpsSparks(pos) {
      const n = 14;
      const arr = new Float32Array(n * 3);
      for (let i = 0; i < n; i++) {
        arr[i * 3] = pos.x + (Math.random() - .5) * 0.6;
        arr[i * 3 + 1] = 0.8 + Math.random() * 0.9;
        arr[i * 3 + 2] = pos.z + (Math.random() - .5) * 0.6;
      }
      const geo = new THREE.BufferGeometry();
      geo.setAttribute("position", new THREE.BufferAttribute(arr, 3));
      const pts = new THREE.Points(geo, new THREE.PointsMaterial({ color: 0xffaa55, size: 0.1, transparent: true, opacity: 0.9 }));
      FPS_STATE.scene.add(pts);
      FPS_STATE.tracers.push({ line: pts, life: 0.3, maxLife: 0.3 });
    }

    function fpsPointBlocked(x, z) {
      for (const o of FPS_STATE.obstacles) {
        if (x > o.minX && x < o.maxX && z > o.minZ && z < o.maxZ) return true;
      }
      return false;
    }

    // 机器人模型（机器人敌人与多人模式远程玩家共用，头部分为爆头判定）
    function makeFpsRobotModel() {
      const g = new THREE.Group();
      const bodyMat = new THREE.MeshPhongMaterial({ color: 0x9aa2ab });
      const body = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.75, 0.36), bodyMat);
      body.position.y = 1.05;
      body.userData.part = "body";
      const head = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.28, 0.3), new THREE.MeshPhongMaterial({ color: 0x23272b }));
      head.position.y = 1.56;
      head.userData.part = "head";
      const eye = new THREE.Mesh(new THREE.SphereGeometry(0.06, 8, 8), new THREE.MeshBasicMaterial({ color: 0xff3030 }));
      eye.position.set(0, 1.58, 0.17);
      const legL = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.5, 0.18), bodyMat);
      legL.position.set(-0.16, 0.25, 0);
      legL.userData.part = "body";
      const legR = legL.clone();
      legR.position.x = 0.16;
      const armL = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.5, 0.14), bodyMat);
      armL.position.set(-0.4, 1.15, 0);
      armL.userData.part = "body";
      const armR = armL.clone();
      armR.position.x = 0.4;
      g.add(body, head, eye, legL, legR, armL, armR);
      return { group: g, bodyMat, hitMeshes: [body, head, legL, legR, armL, armR] };
    }

    // 远程玩家使用独立的醒目模型，避免与房间内机器人敌人混淆。
    function makeFpsPlayerModel() {
      const model = makeFpsRobotModel();
      model.bodyMat.color.setHex(0x2388d9);
      if (model.hitMeshes[1] && model.hitMeshes[1].material && model.hitMeshes[1].material.color) {
        model.hitMeshes[1].material.color.setHex(0x102c58);
      }
      model.group.scale.setScalar(FPS_REMOTE_PLAYER_SCALE);
      model.group.userData.remotePlayer = true;
      return model;
    }

    function fpsApplyPeerCrouch(peer, crouched) {
      const crouchRatio = crouched ? FPS_PLAYER.heightCrouch / FPS_PLAYER.heightStand : 1;
      peer.group.scale.set(FPS_REMOTE_PLAYER_SCALE, FPS_REMOTE_PLAYER_SCALE * crouchRatio, FPS_REMOTE_PLAYER_SCALE);
      if (peer.label) {
        if (!Number.isFinite(peer.label.userData.baseScaleY)) peer.label.userData.baseScaleY = peer.label.scale.y;
        peer.label.scale.y = peer.label.userData.baseScaleY / crouchRatio;
      }
      peer.group.updateMatrixWorld(true);
    }

    function spawnFpsEnemy(options) {
      const settings = options || {};
      const isBoss = settings.isBoss === true;
      const hp = isBoss ? FPS_BOSS.hp : randInt(FPS_ENEMY.hpMin, FPS_ENEMY.hpMax);
      const enemy = {
        hp, pos: new THREE.Vector3(), flashTimer: 0, attackCd: 0,
        speed: isBoss ? FPS_BOSS.speed : FPS_ENEMY.speed,
        attackDamage: isBoss ? FPS_BOSS.attackDamage : FPS_ENEMY.attackDamage,
        isBoss, remoteBoss: settings.remoteBoss === true, generation: Number(settings.generation) || 0,
        remoteBot: settings.remoteBot === true,
        botId: settings.botId != null ? String(settings.botId)
          : (!isBoss && FPS_STATE.mp.mode === "host" ? "b" + (++FPS_STATE.botSequence) : null),
        bodyMat: null, bodyColor: isBoss ? 0xb51e2c : 0x9aa2ab,
        nextMinionAt: 0
      };
      const model = makeFpsRobotModel();
      enemy.bodyMat = model.bodyMat;
      if (isBoss) {
        model.bodyMat.color.setHex(enemy.bodyColor);
        if (model.hitMeshes[1] && model.hitMeshes[1].material && model.hitMeshes[1].material.color) {
          model.hitMeshes[1].material.color.setHex(0x630b10);
        }
        model.group.scale.setScalar(FPS_BOSS.scale);
      }
      const g = model.group;
      enemy.group = g;
      enemy.hitMeshes = model.hitMeshes;
      model.hitMeshes.forEach((m) => { m.userData.enemy = enemy; });
      let x, z, tries = 0;
      if (settings.position && Number.isFinite(settings.position.x) && Number.isFinite(settings.position.z)) {
        x = Math.max(-27.5, Math.min(27.5, settings.position.x));
        z = Math.max(-27.5, Math.min(27.5, settings.position.z));
      } else do {
          const ang = Math.random() * Math.PI * 2;
          const dist = settings.near ? 2.5 + Math.random() * 3 : 16 + Math.random() * 11;
          const originX = settings.near ? settings.near.x : 0;
          const originZ = settings.near ? settings.near.z : 0;
          x = Math.max(-27.5, Math.min(27.5, originX + Math.cos(ang) * dist));
          z = Math.max(-27.5, Math.min(27.5, originZ + Math.sin(ang) * dist));
          tries++;
        } while (fpsPointBlocked(x, z) && tries < 24);
      g.position.set(x, 0, z);
      enemy.pos.set(x, 0, z);
      if (isBoss) enemy.nextMinionAt = Number.isFinite(settings.nextMinionAt)
        ? settings.nextMinionAt : performance.now() + FPS_BOSS.minionDelayMs;
      FPS_STATE.scene.add(g);
      FPS_STATE.enemies.push(enemy);
      FPS_STATE.enemyMeshes.push(...model.hitMeshes);
      if (!isBoss && !enemy.remoteBot) FPS_STATE.spawnCount++;
      return enemy;
    }

    function updateFpsEnemies(dt) {
      // 多人成员端不运行机器人 AI；只对房主广播的普通机器人快照做视觉插值。
      if (FPS_STATE.mp.mode === "member") {
        for (const e of FPS_STATE.enemies) {
          if (e.isBoss || !e.remoteBot) continue;
          const targetX = Number.isFinite(e.tx) ? e.tx : e.pos.x;
          const targetZ = Number.isFinite(e.tz) ? e.tz : e.pos.z;
          const blend = Math.min(1, Math.max(0, dt * 12));
          e.pos.x += (targetX - e.pos.x) * blend;
          e.pos.z += (targetZ - e.pos.z) * blend;
          e.group.position.set(e.pos.x, 0, e.pos.z);
          if (Number.isFinite(e.tyaw)) e.group.rotation.y = e.tyaw;
        }
        return;
      }
      const dir = new THREE.Vector3();
      for (let i = FPS_STATE.enemies.length - 1; i >= 0; i--) {
        const e = FPS_STATE.enemies[i];
        if (e.isBoss && e.remoteBoss) {
          e.group.position.set(e.pos.x, 0, e.pos.z);
          continue;
        }
        let target = FPS_STATE.player;
        let targetPeer = null;
        if (FPS_STATE.mp.mode === "host") {
          let bestDistance = target && !FPS_STATE.mp.dead
            ? Math.hypot(target.x - e.pos.x, target.z - e.pos.z) : Infinity;
          FPS_STATE.mp.peers.forEach((peer) => {
            if (!peer.alive) return;
            const distance = Math.hypot(peer.tx - e.pos.x, peer.tz - e.pos.z);
            if (distance < bestDistance) {
              bestDistance = distance;
              targetPeer = peer;
              target = { x: peer.tx, z: peer.tz };
            }
          });
        }
        if (!target || (FPS_STATE.mp.mode !== "solo" && FPS_STATE.mp.dead && !targetPeer)) continue;
        dir.set(target.x - e.pos.x, 0, target.z - e.pos.z);
        const dist = dir.length();
        if (dist > 0.01) dir.divideScalar(dist);
        if (dist > FPS_ENEMY.attackRange) {
          const step = e.speed * dt;
          const nx = e.pos.x + dir.x * step;
          const nz = e.pos.z + dir.z * step;
          if (!fpsPointBlocked(nx, nz)) {
            e.pos.x = nx;
            e.pos.z = nz;
          } else {
            if (!fpsPointBlocked(e.pos.x + dir.x * step, e.pos.z)) e.pos.x += dir.x * step;
            else if (!fpsPointBlocked(e.pos.x, e.pos.z + dir.z * step)) e.pos.z += dir.z * step;
          }
          e.group.position.set(e.pos.x, 0, e.pos.z);
          e.group.rotation.y = Math.atan2(dir.x, dir.z);
        }
        e.attackCd -= dt;
        if (dist <= FPS_ENEMY.attackRange && e.attackCd <= 0) {
          e.attackCd = FPS_ENEMY.attackCd;
          if (targetPeer && FPS_STATE.mp.mode === "host") {
            if (e.isBoss) {
              sendProtocol("/mp_boss_attack|" + encodeBase64Utf8(targetPeer.name) + "|" + e.generation);
            } else if (e.botId) {
              sendProtocol("/mp_bot_attack|" + encodeBase64Utf8(targetPeer.name) + "|"
                + e.attackDamage + "|" + encodeBase64Utf8(e.botId));
            }
          } else {
            fpsDamagePlayer(e.attackDamage);
          }
        }
        if (e.flashTimer > 0) {
          e.flashTimer -= dt;
          if (e.flashTimer <= 0 && e.bodyMat) e.bodyMat.color.setHex(e.bodyColor);
        }
      }
    }

    function updateFpsSpawn(dt) {
      if (FPS_STATE.mp.mode === "member") return;
      const now = performance.now();
      if (now >= FPS_STATE.nextSpawnAt) {
        spawnFpsEnemy();
        const n = FPS_STATE.spawnCount;
        let interval;
        if (FPS_STATE.mp.mode === "solo") {
          if (n <= 10) interval = 8;
          else if (n <= 20) interval = 6;
          else if (n <= 30) interval = 4;
          else if (n <= 90) interval = 3;
          else interval = 1.5;
        } else {
          // 多人模式保留原有机器人刷新节奏。
          if (n <= 10) interval = 12;
          else if (n <= 30) interval = 6;
          else if (n <= 80) interval = 4;
          else interval = 2;
        }
        FPS_STATE.nextSpawnAt = now + interval * 1000;
      }
    }

    function mpBroadcastBotStates() {
      if (FPS_STATE.mp.mode !== "host" || FPS_STATE.phase !== "playing") return;
      for (const enemy of FPS_STATE.enemies) {
        if (enemy.isBoss || !enemy.botId) continue;
        sendProtocol("/mp_bot_state|" + encodeBase64Utf8(enemy.botId) + "|"
          + Math.max(0, Math.round(enemy.hp)) + "|" + enemy.pos.x.toFixed(3) + "|"
          + enemy.pos.z.toFixed(3) + "|" + enemy.group.rotation.y.toFixed(4) + "|1");
      }
    }

    function mpFindBot(botId) {
      if (!botId) return null;
      return FPS_STATE.enemies.find((enemy) => !enemy.isBoss && enemy.botId === String(botId)) || null;
    }

    function fpsApplyMpBotHitRequest(attacker, botId, damage, weaponIndex, attackerLifeGeneration) {
      if (FPS_STATE.mp.mode !== "host" || FPS_STATE.phase !== "playing") return;
      if (attacker !== state.nickname) {
        const peer = FPS_STATE.mp.peers.get(attacker);
        if (!peer || !peer.alive || peer.lifeGeneration !== attackerLifeGeneration) return;
      } else if (attackerLifeGeneration !== FPS_STATE.mp.lifeGeneration || FPS_STATE.mp.dead) {
        return;
      }
      const enemy = mpFindBot(botId);
      if (!enemy || !Number.isFinite(damage) || damage <= 0 || !Number.isInteger(weaponIndex)
        || !Number.isSafeInteger(attackerLifeGeneration)) return;
      enemy.hp -= damage;
      enemy.lastWeaponIndex = weaponIndex;
      enemy.lastAttacker = attacker || state.nickname;
      enemy.flashTimer = 0.09;
      if (enemy.bodyMat) enemy.bodyMat.color.setHex(0xff9a9a);
      if (enemy.hp <= 0) {
        const killer = enemy.lastAttacker || state.nickname;
        const id = enemy.botId;
        removeFpsEnemy(enemy);
        sendProtocol("/mp_bot_kill|" + encodeBase64Utf8(id) + "|" + encodeBase64Utf8(killer) + "|"
          + weaponIndex + "|" + attackerLifeGeneration);
      }
      mpBroadcastBotStates();
    }

    function applyMpBotState(botId, hp, x, z, yaw, alive) {
      if (FPS_STATE.mp.mode === "solo" || !botId || ![hp, x, z, yaw].every(Number.isFinite)) return;
      let enemy = mpFindBot(botId);
      if (!alive || hp <= 0) {
        if (enemy) removeFpsEnemy(enemy);
        return;
      }
      if (!enemy) {
        enemy = spawnFpsEnemy({ remoteBot: true, botId, position: { x, z } });
      }
      enemy.hp = hp;
      enemy.tx = x;
      enemy.tz = z;
      enemy.tyaw = yaw;
      if (FPS_STATE.mp.mode === "host") {
        enemy.pos.set(x, 0, z);
        enemy.group.position.set(x, 0, z);
        enemy.group.rotation.y = yaw;
      }
    }

    function handleMpBotRemove(botId, attacker, weaponIndex, attackerLifeGeneration) {
      if (FPS_STATE.mp.mode === "solo") return;
      const enemy = mpFindBot(botId);
      if (enemy) removeFpsEnemy(enemy);
      if (attacker === state.nickname && attackerLifeGeneration === FPS_STATE.mp.lifeGeneration) {
        const gun = FPS_STATE.guns[weaponIndex];
        const reward = gun && gun.config && Number.isSafeInteger(gun.config.botKillReserve)
          ? gun.config.botKillReserve : 0;
        if (reward > 0) {
          gun.reserve += reward;
          if (weaponIndex === FPS_STATE.weaponIndex) updateFpsHud();
          showToast("☠ 击杀机器人，" + gun.config.name + " 备弹 +" + reward, "success", 1800);
        }
      }
    }

    function updateFpsBoss(now) {
      if (FPS_STATE.mp.mode !== "solo" && FPS_STATE.mp.mode !== "host") return;
      const boss = FPS_STATE.boss;
      if (!boss) {
        if (now >= FPS_STATE.nextBossAt) {
          const angle = Math.random() * Math.PI * 2;
          const distance = 16 + Math.random() * 11;
          const x = Math.max(-27.5, Math.min(27.5, FPS_STATE.player.x + Math.cos(angle) * distance));
          const z = Math.max(-27.5, Math.min(27.5, FPS_STATE.player.z + Math.sin(angle) * distance));
          if (FPS_STATE.mp.mode === "solo") {
            FPS_STATE.boss = spawnFpsEnemy({ isBoss: true, position: { x, z } });
            showToast("⚠ 红色机器人 Boss 已出现", "error", 2600);
          } else {
            sendProtocol("/mp_boss_spawn|" + x + "|" + z);
            FPS_STATE.nextBossAt = now + 5000;
          }
        }
        return;
      }
      if (FPS_STATE.mp.mode === "host" && now - (boss.lastSyncAt || 0) >= 250) {
        boss.lastSyncAt = now;
        sendProtocol("/mp_boss_move|" + boss.generation + "|" + boss.pos.x + "|" + boss.pos.z);
      }
      if (now < boss.nextMinionAt) return;
      if (FPS_STATE.mp.mode === "solo") {
        for (let i = 0; i < FPS_BOSS.minionCount; i++) {
          spawnFpsEnemy({ near: boss.pos });
        }
        boss.nextMinionAt = now + FPS_BOSS.minionDelayMs;
      } else {
        sendProtocol("/mp_boss_wave|" + boss.generation + "|" + boss.pos.x + "|" + boss.pos.z);
        // 服务器以 10 秒为准节流波次，房主收到广播后才实际生成机器人。
        boss.nextMinionAt = now + FPS_BOSS.minionDelayMs;
        return;
      }
      showToast("🤖 Boss 召唤了 10 个机器人", "info", 1800);
    }

    function fpsDamagePlayer(amount) {
      const p = FPS_STATE.player;
      if (!p) return;
      if (FPS_STATE.mp.mode !== "solo") {
        fpsDamagePlayerByBotMp(amount);
        return;
      }
      p.hp -= amount;
      ui.fpsDamageFlash.hidden = false;
      if (FPS_STATE.damageFlashTimer) window.clearTimeout(FPS_STATE.damageFlashTimer);
      FPS_STATE.damageFlashTimer = window.setTimeout(() => { ui.fpsDamageFlash.hidden = true; }, 120);
      updateFpsHud();
      if (p.hp <= 0) {
        p.hp = 0;
        updateFpsHud();
        fpsSettle();
      }
    }

    function fpsDamagePlayerByBotMp(amount) {
      const p = FPS_STATE.player;
      if (!p || FPS_STATE.mp.dead || !Number.isFinite(amount) || amount <= 0) return;
      p.hp -= amount;
      ui.fpsDamageFlash.hidden = false;
      if (FPS_STATE.damageFlashTimer) window.clearTimeout(FPS_STATE.damageFlashTimer);
      FPS_STATE.damageFlashTimer = window.setTimeout(() => { ui.fpsDamageFlash.hidden = true; }, 120);
      updateFpsHud();
      if (p.hp <= 0) {
        p.hp = 0;
        FPS_STATE.mp.dead = true;
        FPS_STATE.mouseDown = false;
        FPS_STATE.mp.respawnAt = performance.now() + 3000;
        ui.fpsRespawnOverlay.hidden = false;
        ui.fpsRespawnOverlay.textContent = "☠ 你被机器人击杀\n3 秒后复活";
        mpBroadcastState();
      }
    }

    function updateFpsHud() {
      const weapon = FPS_STATE.gun && FPS_STATE.gun.config
        ? FPS_STATE.gun.config
        : (WEAPONS[FPS_STATE.weaponIndex] || WEAPONS[0]);
      ui.fpsHp.textContent = String(FPS_STATE.player ? FPS_STATE.player.hp : FPS_PLAYER.hp);
      ui.fpsAmmo.textContent = String(FPS_STATE.gun ? FPS_STATE.gun.mag : weapon.magSize);
      ui.fpsReserve.textContent = String(FPS_STATE.gun ? FPS_STATE.gun.reserve : weapon.reserve);
      ui.fpsScore.textContent = String(FPS_STATE.score);
      if (ui.fpsWeaponInfo) ui.fpsWeaponInfo.textContent = weapon.name;
    }

    function updateFpsGun(dt) {
      const g = FPS_STATE.gun;
      if (g && g.reloading) {
        g.reloadLeft -= dt;
        if (g.reloadLeft <= 0) {
          g.reloading = false;
          const take = Math.min(g.config.magSize - g.mag, g.reserve);
          g.mag += take;
          g.reserve -= take;
          updateFpsHud();
        }
      }
      if (FPS_STATE.flashLife > 0) {
        FPS_STATE.flashLife -= dt;
        if (FPS_STATE.flashLife <= 0 && FPS_STATE.flashMesh) FPS_STATE.flashMesh.visible = false;
      }
      for (let i = FPS_STATE.tracers.length - 1; i >= 0; i--) {
        const t = FPS_STATE.tracers[i];
        t.life -= dt;
        if (t.life <= 0) {
          FPS_STATE.scene.remove(t.line);
          if (t.line.geometry) t.line.geometry.dispose();
          if (t.line.material) t.line.material.dispose();
          FPS_STATE.tracers.splice(i, 1);
        } else if (t.line.material) {
          t.line.material.opacity = Math.max(0, t.life / t.maxLife);
        }
      }
      if (FPS_STATE.gunGroup) {
        const moving = FPS_STATE.phase === "playing" && FPS_STATE.player
          && (FPS_STATE.keys["KeyW"] || FPS_STATE.keys["KeyS"] || FPS_STATE.keys["KeyA"] || FPS_STATE.keys["KeyD"]);
        const bob = moving ? Math.sin(performance.now() / 160) * 0.005 : 0;
        const lunge = FPS_STATE.mp.meleeAnim > 0 ? -0.22 * Math.sin(FPS_STATE.mp.meleeAnim / 0.22 * Math.PI) : 0;
        FPS_STATE.gunGroup.position.y = -0.28 + bob;
        FPS_STATE.gunGroup.position.z = -0.55 + lunge;
      }
    }

    // ==================== 多人模式 ====================
    function mpOpenPanel() {
      ui.fpsMenu.hidden = true;
      ui.fpsMpPanel.hidden = false;
      mpRefreshList();
    }

    function mpClosePanel() {
      ui.fpsMpPanel.hidden = true;
      ui.fpsMenu.hidden = false;
    }

    function mpRefreshList() {
      sendProtocol("/mp_list");
    }

    function renderMpServers(rows) {
      const el = ui.fpsMpServers;
      el.replaceChildren();
      if (!rows.length) {
        const empty = document.createElement("div");
        empty.className = "earth-info-empty";
        empty.textContent = "暂无在线服务器，创建一个吧！";
        el.appendChild(empty);
        return;
      }
      rows.forEach((row) => {
        const item = document.createElement("div");
        item.className = "fps-mp-server";
        const info = document.createElement("div");
        const host = document.createElement("div");
        host.className = "host";
        host.textContent = "🏠 " + row.host + (row.bots === "1" ? " · 🤖开" : "");
        const meta = document.createElement("div");
        meta.className = "meta";
        meta.textContent = (row.pass === "1" ? "🔒 需密码" : "🌍 公开") + " · " + row.current + "/" + row.max + " 人";
        info.append(host, meta);
        const joinBtn = document.createElement("button");
        joinBtn.className = "secondary";
        joinBtn.type = "button";
        joinBtn.textContent = "加入";
        joinBtn.addEventListener("click", () => {
          fpsPrepareMpMusic();
          if (row.pass === "1") {
            const pass = window.prompt("请输入服务器密码（" + row.host + " 的房间）", "");
            if (pass === null) {
              fpsStopMpMusic();
              return;
            }
            sendProtocol("/mp_join|" + row.id + "|" + pass);
          } else {
            sendProtocol("/mp_join|" + row.id + "|");
          }
          joinBtn.disabled = true;
        });
        item.append(info, joinBtn);
        el.appendChild(item);
      });
    }

    function mpCreate() {
      const max = Number(ui.fpsMpMax.value);
      if (!Number.isInteger(max) || max < 2 || max > 16) {
        showToast("人数需在 2~16 之间", "error");
        return;
      }
      const pass = ui.fpsMpCreatePass.value.trim();
      ui.fpsMpCreateButton.disabled = true;
      fpsPrepareMpMusic();
      sendProtocol("/mp_create|" + max + "|" + pass);
    }

    function mpJoin(id) {
      fpsPrepareMpMusic();
      sendProtocol("/mp_join|" + id + "|");
    }

    // 进入多人游戏（房主或成员）
    function mpEnterGame(mode, serverId, hostName, musicStartAt, serverNow) {
      const g = fpsResetWeaponLoadout();
      const w = g.config;
      FPS_STATE.player = {
        x: 0, z: 6, y: 0, prevY: 0, vy: 0, onGround: true, yaw: 0, pitch: 0,
        crouched: false, hp: FPS_PLAYER.hp
      };
      // 随机出生点
      let tries = 0;
      do {
        FPS_STATE.player.x = (Math.random() - .5) * 40;
        FPS_STATE.player.z = (Math.random() - .5) * 40;
        tries++;
      } while (fpsPointBlocked(FPS_STATE.player.x, FPS_STATE.player.z) && tries < 24);
      FPS_STATE.spawnCount = 0;
      FPS_STATE.botSequence = 0;
      // 多人普通机器人刷新节奏保持原值。
      FPS_STATE.nextSpawnAt = performance.now() + 12000;
      FPS_STATE.boss = null;
      FPS_STATE.nextBossAt = performance.now() + FPS_BOSS.spawnDelayMs;
      FPS_STATE.score = 0;
      FPS_STATE.mp.mode = mode;
      FPS_STATE.mp.serverId = serverId;
      // 同一多人游戏房间的所有玩家都进入同一张共享地图。
      FPS_STATE.mp.mapId = "fps-shared-arena-v1";
      FPS_STATE.mp.hostName = hostName || "";
      FPS_STATE.mp.botsEnabled = true;
      FPS_STATE.mp.bossGeneration = 0;
      FPS_STATE.mp.bossSyncTimer = 0;
      FPS_STATE.mp.meleeCd = 0;
      FPS_STATE.mp.dead = false;
      mpAdvanceLifeGeneration();
      FPS_STATE.mp.broadcastTimer = 0;
      FPS_STATE.mp.botSnapshotTimer = 0;
      FPS_STATE.mp.peers.forEach((peer) => removePeer(peer.name));
      FPS_STATE.mp.peers.clear();
      FPS_STATE.mp.peerHitMeshes = [];
      FPS_STATE.enemies.forEach((e) => FPS_STATE.scene.remove(e.group));
      FPS_STATE.enemies = [];
      FPS_STATE.enemyMeshes = [];
      FPS_STATE.tracers.forEach((t) => FPS_STATE.scene.remove(t.line));
      FPS_STATE.tracers = [];
      FPS_STATE.phase = "playing";
      FPS_STATE.startedAt = Date.now();
      ui.fpsMenu.hidden = true;
      ui.fpsMpPanel.hidden = true;
      ui.fpsSettle.hidden = true;
      ui.fpsHud.hidden = false;
      ui.fpsDamageFlash.hidden = true;
      // 多人模式隐藏积分，显示在线人数与自己的用户 id
      ui.fpsScoreWrap.hidden = true;
      ui.fpsSelfId.hidden = false;
      ui.fpsSelfId.textContent = "👤 " + state.nickname;
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsMpInfo.textContent = "🌐 多人" + (mode === "host" ? "（房主）" : " · 房主 " + (hostName || "")) + " · 在线 1 人";
      ui.fpsWeaponInfo.textContent = w.name;
      fpsApplyWeaponAppearance(w);
      updateFpsHud();
      mpInitPickups();
      fpsStartMpMusic(musicStartAt, serverNow);
      mpBroadcastState();
      // 确保房间地图、公共道具和玩家快照在本地场景初始化后再应用一次。
      sendProtocol("/mp_world_request");
      if (ui.tBtnJ) ui.tBtnJ.disabled = mode !== "host"; // J 仅房主可用
      fpsTrapHistory();
      fpsRequestLockScreen();
    }

    // 退出多人游戏：退出服务器（房主退出即删除服务器）并回到菜单
    function mpExitGame(showMpPanel) {
      fpsStopMpMusic();
      if (FPS_STATE.mp.mode !== "solo") {
        sendProtocol("/mp_leave");
      }
      FPS_STATE.mp.mode = "solo";
      FPS_STATE.mp.serverId = null;
      FPS_STATE.mp.mapId = null;
      FPS_STATE.mp.hostName = "";
      FPS_STATE.mp.dead = false;
      FPS_STATE.mp.peers.forEach((peer) => removePeer(peer.name));
      FPS_STATE.mp.peers.clear();
      FPS_STATE.mp.peerHitMeshes = [];
      FPS_STATE.enemies.forEach((e) => FPS_STATE.scene.remove(e.group));
      FPS_STATE.enemies = [];
      FPS_STATE.enemyMeshes = [];
      FPS_STATE.boss = null;
      FPS_STATE.nextBossAt = 0;
      if (FPS_STATE.mp.pickups) {
         if (FPS_STATE.mp.pickups.blood) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.blood);
         if (FPS_STATE.mp.pickups.ammo) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.ammo);
        FPS_STATE.mp.pickups = null;
      }
      if (document.exitFullscreen && document.fullscreenElement) document.exitFullscreen().catch(() => { });
      if (document.exitPointerLock && document.pointerLockElement) document.exitPointerLock();
      setFpsTouchControls(false);
      ui.fpsSelfId.hidden = true;
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsPause.hidden = true;
      FPS_STATE.paused = false;
      fpsUntrapHistory();
      FPS_STATE.phase = "menu";
      ui.fpsHud.hidden = true;
      ui.fpsSettle.hidden = true;
      ui.fpsScoreWrap.hidden = false;
      if (showMpPanel) {
        ui.fpsMenu.hidden = true;
        ui.fpsMpPanel.hidden = false;
        mpRefreshList();
      } else {
        ui.fpsMpPanel.hidden = true;
        ui.fpsMenu.hidden = false;
      }
    }

    function mpBroadcastState() {
      if (FPS_STATE.mp.mode === "solo" || FPS_STATE.phase !== "playing" || !FPS_STATE.player) return;
      const p = FPS_STATE.player;
      const data = [p.x.toFixed(2), p.y.toFixed(2), p.z.toFixed(2), p.yaw.toFixed(3), p.pitch.toFixed(3),
        Math.max(0, p.hp), FPS_STATE.gun.mag, FPS_STATE.gun.reserve, p.crouched ? 1 : 0, FPS_STATE.mp.dead ? 0 : 1,
        FPS_STATE.mp.lifeGeneration]
        .join("|");
      sendProtocol("/mp_state|" + data);
    }

    function makeFpsNameLabel(name) {
      const W = 320, H = 56;
      const c = document.createElement("canvas");
      c.width = W;
      c.height = H;
      const g = c.getContext("2d");
      g.font = "bold 30px 'Microsoft YaHei UI','PingFang SC',sans-serif";
      g.textAlign = "center";
      g.textBaseline = "middle";
      g.lineWidth = 6;
      g.strokeStyle = "rgba(8,12,16,.9)";
      g.strokeText(name, W / 2, H / 2);
      g.fillStyle = "#ffffff";
      g.fillText(name, W / 2, H / 2);
      const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false }));
      sprite.scale.set(0.7, 0.122, 1);
      return sprite;
    }

    function upsertPeer(name, data) {
      if (!name || data.length < 11) return;
      const x = Number(data[0]), y = Number(data[1]), z = Number(data[2]);
      const yaw = Number(data[3]), pitch = Number(data[4]);
      const hp = Number(data[5]), mag = Number(data[6]), reserve = Number(data[7]);
      const crouched = data[8] === "1";
      const alive = data[9] === "1";
      const lifeGeneration = Number(data[10]);
      if (![x, y, z, yaw, pitch, hp, mag, reserve].every(Number.isFinite)
        || !Number.isSafeInteger(lifeGeneration) || lifeGeneration < 1) return;
      let peer = FPS_STATE.mp.peers.get(name);
      if (peer && Number.isSafeInteger(peer.lifeGeneration) && lifeGeneration < peer.lifeGeneration) return;
      if (peer && !peer.alive && alive && lifeGeneration === peer.lifeGeneration) return;
      if (!peer) {
        const model = makeFpsPlayerModel();
        model.hitMeshes.forEach((m) => { m.userData.peer = { name }; });
        FPS_STATE.scene.add(model.group);
        const label = makeFpsNameLabel(name);
        model.group.add(label);
        label.position.y = 2.1;
        peer = { name, group: model.group, label, hitMeshes: model.hitMeshes, bodyMat: model.bodyMat, x, y, z, yaw, pitch, hp, alive, lifeGeneration, labelTimer: 0 };
        FPS_STATE.mp.peers.set(name, peer);
        FPS_STATE.mp.peerHitMeshes.push(...model.hitMeshes);
        updateMpInfo();
      }
      // 首次收到快照时直接定位，之后再由 updateMpPeers 平滑跟随。
      if (!Number.isFinite(peer.group.position.x) || peer.group.position.lengthSq() === 0) {
        peer.group.position.set(x, y, z);
      }
      peer.tx = x; peer.ty = y; peer.tz = z;
      peer.yaw = yaw; peer.pitch = pitch;
      peer.hp = hp; peer.alive = alive; peer.lifeGeneration = lifeGeneration;
      peer.group.visible = alive;
      if (!alive) return;
      peer.crouched = crouched;
      fpsApplyPeerCrouch(peer, crouched);
      // 名字标签显示血量
      peer.labelTimer -= 1;
      if (peer.labelTimer <= 0) {
        peer.labelTimer = 6;
        const label = peer.label;
        if (label.material.map) {
          const W = 320, H = 56;
          const c = document.createElement("canvas");
          c.width = W;
          c.height = H;
          const g = c.getContext("2d");
          g.font = "bold 30px 'Microsoft YaHei UI','PingFang SC',sans-serif";
          g.textAlign = "center";
          g.textBaseline = "middle";
          g.lineWidth = 6;
          g.strokeStyle = "rgba(8,12,16,.9)";
          g.strokeText(name + "  " + Math.max(0, hp), W / 2, H / 2);
          g.fillStyle = hp <= 40 ? "#ff6b6b" : "#ffffff";
          g.fillText(name + "  " + Math.max(0, hp), W / 2, H / 2);
          label.material.map = new THREE.CanvasTexture(c);
          label.material.needsUpdate = true;
        }
      }
    }

    function removePeer(name) {
      const peer = FPS_STATE.mp.peers.get(name);
      if (!peer) return;
      FPS_STATE.scene.remove(peer.group);
      peer.hitMeshes.forEach((m) => {
        const idx = FPS_STATE.mp.peerHitMeshes.indexOf(m);
        if (idx >= 0) FPS_STATE.mp.peerHitMeshes.splice(idx, 1);
      });
      FPS_STATE.mp.peers.delete(name);
      updateMpInfo();
    }

    function updateMpInfo() {
      if (FPS_STATE.mp.mode === "solo") return;
      const count = FPS_STATE.mp.peers.size + 1;
      ui.fpsMpInfo.textContent = "🌐 多人" + (FPS_STATE.mp.mode === "host" ? "（房主）" : " · 房主 " + FPS_STATE.mp.hostName)
        + " · 在线 " + count + " 人";
    }

    function updateMpPeers(dt) {
      FPS_STATE.mp.peers.forEach((peer) => {
        if (!peer.alive) return;
        const k = Math.min(1, dt * 12);
        peer.group.position.x += (peer.tx - peer.group.position.x) * k;
        peer.group.position.y += (peer.ty - peer.group.position.y) * k;
        peer.group.position.z += (peer.tz - peer.group.position.z) * k;
        peer.group.rotation.y = Math.atan2(-Math.sin(peer.yaw), -Math.cos(peer.yaw));
      });
    }

    // 近战：C 键，贴脸 75 伤害，无爆头加成
    function fpsMelee() {
      const mp = FPS_STATE.mp;
      if (mp.meleeCd > 0 || FPS_STATE.phase !== "playing" || !FPS_STATE.player || FPS_STATE.mp.dead) return;
      mp.meleeCd = 0.7;
      mp.meleeAnim = 0.22;
      const cam = FPS_STATE.camera;
      const pos = cam.position.clone();
      const dir = new THREE.Vector3(0, 0, -1).applyQuaternion(cam.quaternion);
      let best = 2.2;
      let targetPeer = null;
      // 远程玩家
      FPS_STATE.mp.peers.forEach((peer) => {
        if (!peer.alive) return;
        const to = peer.group.position.clone().sub(pos);
        const d = to.length();
        if (d <= best && dir.dot(to.clone().normalize()) > 0.45) {
          best = d;
          targetPeer = peer;
        }
      });
      const weaponIndex = FPS_STATE.weaponIndex;
      if (targetPeer && Number.isSafeInteger(targetPeer.lifeGeneration) && targetPeer.lifeGeneration > 0
        && Number.isSafeInteger(FPS_STATE.mp.lifeGeneration) && FPS_STATE.mp.lifeGeneration > 0
        && Number.isInteger(weaponIndex) && weaponIndex >= 0 && weaponIndex < FPS_STATE.guns.length) {
        sendProtocol("/mp_melee|" + targetPeer.name + "|" + weaponIndex + "|"
          + FPS_STATE.mp.lifeGeneration + "|" + targetPeer.lifeGeneration);
      }
      // 本地机器人
      for (let i = FPS_STATE.enemies.length - 1; i >= 0; i--) {
        const e = FPS_STATE.enemies[i];
        const to = e.pos.clone().setY(0).sub(pos.clone().setY(0));
        const d = to.length();
        if (d <= best && dir.clone().setY(0).normalize().dot(to.clone().normalize()) > 0.45) {
          if (e.isBoss && FPS_STATE.mp.mode !== "solo") {
            sendProtocol("/mp_boss_hit|" + e.generation + "|75|" + FPS_STATE.weaponIndex + "|"
              + FPS_STATE.mp.lifeGeneration);
          } else if (FPS_STATE.mp.mode !== "solo" && e.botId) {
            if (FPS_STATE.mp.mode === "host") {
              fpsApplyMpBotHitRequest(state.nickname, e.botId, 75, FPS_STATE.weaponIndex,
                FPS_STATE.mp.lifeGeneration);
            } else {
              sendProtocol("/mp_bot_hit|" + encodeBase64Utf8(e.botId) + "|75|" + FPS_STATE.weaponIndex + "|"
                + FPS_STATE.mp.lifeGeneration);
            }
          } else {
            e.hp -= 75;
            e.lastWeaponIndex = FPS_STATE.weaponIndex;
            e.flashTimer = 0.09;
            if (e.bodyMat) e.bodyMat.color.setHex(0xff9a9a);
            if (e.hp <= 0) fpsKillEnemy(e);
          }
        }
      }
      if (FPS_STATE.mp.mode === "solo" && FPS_STATE.phase === "playing") {
        // 单人模式也可近战
      }
    }

    function clearLocalFpsBoss(countScore) {
      const boss = FPS_STATE.boss;
      if (!boss) return false;
      removeFpsEnemy(boss);
      FPS_STATE.boss = null;
      if (countScore) FPS_STATE.score += 1;
      return true;
    }

    function applyMpBossState(generation, alive, hp, x, z, nextAt, nextMinionAt, serverNow) {
      if (FPS_STATE.mp.mode === "solo" || !Number.isSafeInteger(generation) || generation < 0
        || !Number.isFinite(hp) || hp < 0 || ![x, z, nextAt, nextMinionAt, serverNow].every(Number.isFinite)) return;
      const localNow = performance.now();
      const referenceNow = serverNow > 0 ? serverNow : Date.now();
      const toLocalTime = (serverTime) => localNow + Math.max(0, serverTime - referenceNow);
      if (generation < FPS_STATE.mp.bossGeneration) return;
      FPS_STATE.mp.bossGeneration = generation;
      FPS_STATE.nextBossAt = toLocalTime(nextAt);
      if (!alive) {
        if (clearLocalFpsBoss(true)) showToast("🏆 红色机器人 Boss 已被击败，90 秒后再次出现", "success", 3200);
        return;
      }
      const nextLocalMinionAt = toLocalTime(nextMinionAt);
      let boss = FPS_STATE.boss;
      if (boss && boss.generation !== generation) {
        clearLocalFpsBoss(false);
        boss = null;
      }
      if (!boss) {
        boss = spawnFpsEnemy({ isBoss: true, remoteBoss: FPS_STATE.mp.mode !== "host",
          generation, hp, position: { x, z }, nextMinionAt: nextLocalMinionAt });
        FPS_STATE.boss = boss;
        showToast("⚠ 红色机器人 Boss 已出现", "error", 2600);
      } else {
        boss.hp = hp;
        boss.pos.set(x, 0, z);
        boss.group.position.set(x, 0, z);
        boss.nextMinionAt = nextLocalMinionAt;
      }
    }

    function applyMpBossWave(generation, x, z) {
      if (FPS_STATE.mp.mode === "solo" || generation !== FPS_STATE.mp.bossGeneration
        || !FPS_STATE.boss || !Number.isFinite(x) || !Number.isFinite(z)) return;
      const center = { x, z };
      FPS_STATE.boss.pos.set(x, 0, z);
      FPS_STATE.boss.group.position.set(x, 0, z);
      if (FPS_STATE.mp.mode === "host") {
        for (let i = 0; i < FPS_BOSS.minionCount; i++) spawnFpsEnemy({ near: center });
      }
      FPS_STATE.boss.nextMinionAt = performance.now() + FPS_BOSS.minionDelayMs;
      showToast("🤖 Boss 召唤了 10 个机器人", "info", 1800);
    }

    function handleMpBossKill(generation, attacker) {
      if (FPS_STATE.mp.mode === "solo" || generation !== FPS_STATE.mp.bossGeneration) return;
      if (clearLocalFpsBoss(true)) {
        showToast("🏆 红色机器人 Boss 已被 " + (attacker || "玩家") + " 击败，90 秒后再次出现", "success", 3200);
      }
    }

    function fpsGrantMpBossReward(attackerLifeGeneration, weaponIndex) {
      if (FPS_STATE.phase !== "playing" || attackerLifeGeneration !== FPS_STATE.mp.lifeGeneration
        || !Number.isInteger(weaponIndex) || weaponIndex < 0 || weaponIndex >= FPS_STATE.guns.length) return;
      const gun = FPS_STATE.guns[weaponIndex];
      const reward = gun && gun.config && Number.isSafeInteger(gun.config.botKillReserve)
        ? gun.config.botKillReserve : 0;
      if (reward <= 0) return;
      gun.reserve += reward;
      if (weaponIndex === FPS_STATE.weaponIndex) updateFpsHud();
      showToast("☠ 击杀 Boss，" + gun.config.name + " 备弹 +" + reward, "success", 2200);
    }

    // 地图中心每 60 秒刷新血包与子弹盒
    function mpInitPickups() {
      if (FPS_STATE.mp.pickups) {
        if (FPS_STATE.mp.pickups.blood) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.blood);
        if (FPS_STATE.mp.pickups.ammo) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.ammo);
      }
      // 进入房间时只建立状态容器；道具网格和坐标必须来自服务器 /mp_world 快照。
      FPS_STATE.mp.pickups = {
        blood: null, ammo: null, bloodAt: 0, ammoAt: 0,
        bloodX: 0, bloodY: 0, bloodZ: 0, ammoX: 0, ammoY: 0, ammoZ: 0,
        pendingBlood: false, pendingAmmo: false, worldReady: false
      };
    }

    function applyMpWorldState(mapId, bloodX, bloodY, bloodZ, ammoX, ammoY, ammoZ,
      bloodVisible, bloodAt, ammoVisible, ammoAt) {
      if (FPS_STATE.mp.mode === "solo") return;
      if (mapId !== "fps-shared-arena-v1") {
        showToast("多人游戏房间地图版本不一致", "error", 3000);
        return;
      }
      FPS_STATE.mp.mapId = mapId;
      const pk = FPS_STATE.mp.pickups;
      if (!pk) return;
      if (![bloodX, bloodY, bloodZ, ammoX, ammoY, ammoZ].every(Number.isFinite)) return;
      if (pk.blood) FPS_STATE.scene.remove(pk.blood);
      if (pk.ammo) FPS_STATE.scene.remove(pk.ammo);
      const bloodMat = new THREE.MeshBasicMaterial({ color: 0xff3b3b });
      const ammoMat = new THREE.MeshBasicMaterial({ color: 0xffd75e });
      pk.blood = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.5, 0.42), bloodMat);
      pk.blood.position.set(bloodX, bloodY, bloodZ);
      pk.ammo = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.42, 0.42), ammoMat);
      pk.ammo.position.set(ammoX, ammoY, ammoZ);
      pk.bloodX = bloodX; pk.bloodY = bloodY; pk.bloodZ = bloodZ;
      pk.ammoX = ammoX; pk.ammoY = ammoY; pk.ammoZ = ammoZ;
      FPS_STATE.scene.add(pk.blood);
      FPS_STATE.scene.add(pk.ammo);
      pk.blood.visible = bloodVisible;
      pk.bloodAt = bloodAt;
      pk.pendingBlood = false;
      pk.ammo.visible = ammoVisible;
      pk.ammoAt = ammoAt;
      pk.pendingAmmo = false;
      pk.worldReady = true;
    }

    function applyMpPickupState(type, visible, respawnAt, pickerName) {
      const pk = FPS_STATE.mp.pickups;
      if (!pk || !pk.blood || !pk.ammo || (type !== "blood" && type !== "ammo")) return;
      const item = type === "blood" ? pk.blood : pk.ammo;
      item.visible = visible;
      if (type === "blood") {
        pk.bloodAt = respawnAt;
        pk.pendingBlood = false;
      } else {
        pk.ammoAt = respawnAt;
        pk.pendingAmmo = false;
      }
      if (!visible && pickerName === state.nickname) {
        if (type === "blood") {
          FPS_STATE.player.hp = FPS_PLAYER.hp;
          updateFpsHud();
          showToast("❤ 已拾取房间公用血包，生命回满", "success", 1800);
        } else {
          FPS_STATE.gun.reserve += 60;
          updateFpsHud();
          showToast("📦 已拾取房间公用子弹盒，备弹 +60", "success", 1800);
        }
      }
    }

    function mpUpdatePickups(dt, now) {
      const pk = FPS_STATE.mp.pickups;
      if (!pk || !pk.blood || !pk.ammo || FPS_STATE.mp.mode === "solo") return;
      if (!pk.worldReady) return;
      // 刷新时间来自服务器（Unix 时间），房间内所有玩家看到同一份道具状态。
      // 刷新由服务器广播 /mp_pickup_state|...|1|...，客户端不自行刷新，保证道具全房间共用。
      // 漂浮动画
      pk.blood.position.y = pk.bloodY + Math.sin(now / 400) * 0.12;
      pk.ammo.position.y = pk.ammoY + Math.sin(now / 400 + 1) * 0.12;
      if (FPS_STATE.mp.dead) return;
      const p = FPS_STATE.player;
      if (pk.blood.visible && !pk.pendingBlood && p.hp < FPS_PLAYER.hp) {
        const d = Math.sqrt((p.x - pk.bloodX) * (p.x - pk.bloodX)
          + (p.y - pk.blood.position.y) * (p.y - pk.blood.position.y)
          + (p.z - pk.bloodZ) * (p.z - pk.bloodZ));
        if (d < 1.4) {
          pk.pendingBlood = true;
          sendProtocol("/mp_pickup|blood");
        }
      }
      if (pk.ammo.visible && !pk.pendingAmmo) {
        const d = Math.sqrt((p.x - pk.ammoX) * (p.x - pk.ammoX)
          + (p.y - pk.ammo.position.y) * (p.y - pk.ammo.position.y)
          + (p.z - pk.ammoZ) * (p.z - pk.ammoZ));
        if (d < 1.4) {
          pk.pendingAmmo = true;
          sendProtocol("/mp_pickup|ammo");
        }
      }
    }

    // 多人模式受伤：无结算，死亡后 3 秒重生
    function fpsDamagePlayerMp(dmg, attacker, hitId) {
      const p = FPS_STATE.player;
      if (!p || FPS_STATE.mp.dead || !Number.isFinite(dmg) || dmg <= 0 || typeof hitId !== "string" || !hitId) return;
      p.hp -= dmg;
      ui.fpsDamageFlash.hidden = false;
      if (FPS_STATE.damageFlashTimer) window.clearTimeout(FPS_STATE.damageFlashTimer);
      FPS_STATE.damageFlashTimer = window.setTimeout(() => { ui.fpsDamageFlash.hidden = true; }, 120);
      updateFpsHud();
      if (p.hp <= 0) {
        p.hp = 0;
        FPS_STATE.mp.dead = true;
        FPS_STATE.mouseDown = false;
        FPS_STATE.mp.respawnAt = performance.now() + 3000;
        ui.fpsRespawnOverlay.hidden = false;
        ui.fpsRespawnOverlay.textContent = "☠ 你被 " + attacker + " 击杀\n3 秒后复活";
        mpBroadcastState();
        sendProtocol("/mp_death|" + hitId + "|" + FPS_STATE.mp.lifeGeneration);
      }
    }

    function fpsGrantMpKillReward(attackerLifeGeneration, weaponIndex, reserveAmount, healAmount, victim) {
      if (FPS_STATE.phase !== "playing" || attackerLifeGeneration !== FPS_STATE.mp.lifeGeneration
        || !Number.isInteger(weaponIndex) || weaponIndex < 0 || weaponIndex >= FPS_STATE.guns.length
        || !Number.isSafeInteger(reserveAmount) || reserveAmount < 0
        || !Number.isSafeInteger(healAmount) || healAmount < 0) return;
      const gun = FPS_STATE.guns[weaponIndex];
      if (!gun || !gun.config) return;
      if (reserveAmount > 0) gun.reserve += reserveAmount;
      if (healAmount > 0 && FPS_STATE.player) {
        FPS_STATE.player.hp = Math.min(FPS_PLAYER.hp, FPS_STATE.player.hp + healAmount);
      }
      if (weaponIndex === FPS_STATE.weaponIndex) updateFpsHud();
      const rewards = [];
      if (reserveAmount > 0) rewards.push("备弹 +" + reserveAmount);
      if (healAmount > 0) rewards.push("生命 +" + healAmount);
      if (rewards.length) showToast("☠ 击杀 " + (victim || "对手") + "，" + gun.config.name + " " + rewards.join("，"), "success", 2200);
    }

    function mpRespawnIfNeeded(now) {
      if (!FPS_STATE.mp.dead || now < FPS_STATE.mp.respawnAt) return;
      mpAdvanceLifeGeneration();
      FPS_STATE.mp.dead = false;
      ui.fpsRespawnOverlay.hidden = true;
      const p = FPS_STATE.player;
      p.hp = FPS_PLAYER.hp;
      fpsResetWeaponLoadout();
      let tries = 0;
      do {
        p.x = (Math.random() - .5) * 40;
        p.z = (Math.random() - .5) * 40;
        p.y = 0;
        p.vy = 0;
        tries++;
      } while (fpsPointBlocked(p.x, p.z) && tries < 24);
      updateFpsHud();
      mpBroadcastState();
    }

    // 指针锁定/全屏意外中断（如鼠标误触手势）→ 暂停而非退出，点击可继续
    function fpsShowPause() {
      if (FPS_STATE.phase !== "playing" || FPS_STATE.paused) return;
      FPS_STATE.paused = true;
      ui.fpsPause.hidden = false;
    }

    function fpsResume() {
      if (FPS_STATE.phase !== "playing") return;
      FPS_STATE.paused = false;
      ui.fpsPause.hidden = true;
      if (FPS_STATE.mp.mode !== "solo") fpsSyncMpMusic();
      if (!IS_TOUCH_DEVICE && !document.pointerLockElement) {
        try { FPS_STATE.renderer.domElement.requestPointerLock(); } catch (_) { }
      }
    }

    function fpsPauseExit() {
      if (FPS_STATE.phase !== "playing") return;
      FPS_STATE.paused = false;
      ui.fpsPause.hidden = true;
      if (FPS_STATE.mp.mode !== "solo") mpExitGame(true);
      else fpsSettle();
    }

    // 阻止浏览器手势返回导航导致游戏退出
    function fpsTrapHistory() {
      if (FPS_STATE.historyTrapped) return;
      FPS_STATE.historyTrapped = true;
      try {
        history.pushState({ fps: true }, "");
        FPS_STATE.onPopState = () => {
          if (FPS_STATE.phase === "playing") {
            history.pushState({ fps: true }, "");
            showToast("已阻止浏览器手势导航", "info", 1500);
          }
        };
        window.addEventListener("popstate", FPS_STATE.onPopState);
      } catch (_) { }
    }

    function fpsUntrapHistory() {
      if (FPS_STATE.onPopState) {
        window.removeEventListener("popstate", FPS_STATE.onPopState);
        FPS_STATE.onPopState = null;
      }
      FPS_STATE.historyTrapped = false;
    }

    function fpsSettle() {
      if (FPS_STATE.phase !== "playing") return;
      FPS_STATE.phase = "settled";
      FPS_STATE.mouseDown = false;
      if (document.exitFullscreen && document.fullscreenElement) document.exitFullscreen().catch(() => { });
      if (document.exitPointerLock && document.pointerLockElement) document.exitPointerLock();
      const best = fpsSaveBest(FPS_STATE.score);
      ui.fpsSettleScore.textContent = String(FPS_STATE.score);
      ui.fpsSettleBest.textContent = String(best);
      ui.fpsSettleRank.textContent = "--";
      setFpsTouchControls(false);
      ui.fpsSelfId.hidden = true;
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsPause.hidden = true;
      FPS_STATE.paused = false;
      fpsUntrapHistory();
      ui.fpsHud.hidden = true;
      ui.fpsSettle.hidden = false;
      if (FPS_STATE.score > 0) {
        saveGameRecord(FPS_RECORD_FILE, FPS_GAME_NAME, FPS_STATE.score);
      }
      requestGameRank(FPS_RECORD_FILE, FPS_GAME_NAME);
    }

    function fpsLoop(now) {
      if (!FPS_STATE.running) return;
      FPS_STATE.rafId = window.requestAnimationFrame(fpsLoop);
      const dt = Math.min(0.05, (now - FPS_STATE.lastTime) / 1000);
      FPS_STATE.lastTime = now;
      if (FPS_STATE.phase === "playing") {
        updateFpsPlayer(dt);
        if (FPS_STATE.mp.mode === "solo") {
          updateFpsSpawn(dt);
        } else {
          // 多人模式：只有房主拥有普通机器人的刷新、AI 和命中权威。
          if (FPS_STATE.mp.mode === "host" && FPS_STATE.mp.botsEnabled) updateFpsSpawn(dt);
          if (FPS_STATE.mp.mode === "host") {
            FPS_STATE.mp.botSnapshotTimer += dt;
            if (FPS_STATE.mp.botSnapshotTimer >= 0.1) {
              FPS_STATE.mp.botSnapshotTimer = 0;
              mpBroadcastBotStates();
            }
          }
          FPS_STATE.mp.broadcastTimer += dt;
          if (FPS_STATE.mp.broadcastTimer >= 0.1) {
            FPS_STATE.mp.broadcastTimer = 0;
            mpBroadcastState();
          }
          updateMpPeers(dt);
          if (FPS_STATE.mp.meleeCd > 0) FPS_STATE.mp.meleeCd -= dt;
          if (FPS_STATE.mp.meleeAnim > 0) FPS_STATE.mp.meleeAnim -= dt;
          mpUpdatePickups(dt, Date.now());
          if (FPS_STATE.mp.dead) {
            // 死亡屏幕倒数：3 秒后随机位置复活
            const remain = Math.max(0, Math.ceil((FPS_STATE.mp.respawnAt - performance.now()) / 1000));
            ui.fpsRespawnOverlay.textContent = "☠ 你已被击杀\n" + remain + " 秒后复活";
          }
          mpRespawnIfNeeded(performance.now());
        }
        // Boss 不受房主 J 键影响；J 只控制普通机器人刷新。
        updateFpsBoss(now);
        updateFpsEnemies(dt);
        fpsTryFire(dt);
        updateFpsGun(dt);
      } else if (FPS_STATE.phase === "menu") {
        const t = now * 0.0002;
        FPS_STATE.camera.position.set(Math.sin(t) * 17, 6, Math.cos(t) * 17);
        FPS_STATE.camera.lookAt(0, 1, 0);
        updateFpsGun(dt);
      }
      FPS_STATE.renderer.render(FPS_STATE.scene, FPS_STATE.camera);
    }

    function stopFpsLoop() {
      FPS_STATE.running = false;
      if (FPS_STATE.rafId) window.cancelAnimationFrame(FPS_STATE.rafId);
      FPS_STATE.rafId = 0;
    }

    function enterFps() {
      showScreen("fps");
      initFpsScene();
      if (!FPS_STATE.ready) return;
      startFpsDevtoolsGuard();
      FPS_STATE.phase = "menu";
      ui.fpsMenu.hidden = false;
      ui.fpsHud.hidden = true;
      ui.fpsSettle.hidden = true;
      ui.fpsBestScore.textContent = String(fpsBest());
      if (!FPS_STATE.running) {
        FPS_STATE.running = true;
        FPS_STATE.lastTime = performance.now();
        FPS_STATE.rafId = window.requestAnimationFrame(fpsLoop);
      }
      resizeFps();
    }

    function leaveFps() {
      stopFpsDevtoolsGuard();
      fpsStopMpMusic();
      if (FPS_STATE.mp.mode !== "solo") {
        sendProtocol("/mp_leave");
        FPS_STATE.mp.mode = "solo";
        FPS_STATE.mp.mapId = null;
        FPS_STATE.mp.peers.forEach((peer) => removePeer(peer.name));
        FPS_STATE.mp.peers.clear();
        FPS_STATE.mp.peerHitMeshes = [];
        FPS_STATE.enemies.forEach((e) => FPS_STATE.scene.remove(e.group));
        FPS_STATE.enemies = [];
        FPS_STATE.enemyMeshes = [];
        FPS_STATE.boss = null;
        FPS_STATE.nextBossAt = 0;
        if (FPS_STATE.mp.pickups) {
           if (FPS_STATE.mp.pickups.blood) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.blood);
           if (FPS_STATE.mp.pickups.ammo) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.ammo);
          FPS_STATE.mp.pickups = null;
        }
        ui.fpsScoreWrap.hidden = false;
      }
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => { });
      if (document.pointerLockElement && document.exitPointerLock) document.exitPointerLock();
      setFpsTouchControls(false);
      ui.fpsSelfId.hidden = true;
      ui.fpsRespawnOverlay.hidden = true;
      ui.fpsPause.hidden = true;
      FPS_STATE.paused = false;
      fpsUntrapHistory();
      stopFpsLoop();
      showScreen("games");
    }


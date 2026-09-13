    // ==================== 模拟地球（Three.js 3D 地球） ====================
    const EARTH_RADIUS = 1;
    const EARTH_CAMERA_DIST = 3.1;
    const EARTH_ZOOM_MAX_DIST = EARTH_CAMERA_DIST * 2; // 最远可缩小 2 倍
    const CONFLICT_DURATION_MS = 20 * 60 * 1000; // 冲突持续 20 分钟
    const CONFLICT_MAX = 3; // 最多同时 3 组冲突
    const MISSILE_LOSS_MIN = 8000;
    const MISSILE_LOSS_MAX = 30000;
    const CONTINENT_DEFS = {
      Asia:       { name: "亚洲",   base: 2000000000, cities: [] },
      Europe:     { name: "欧洲",   base: 1500000000, cities: [] },
      Africa:     { name: "非洲",   base: 900000000,  cities: [] },
      Americas:   { name: "美洲",   base: 756300002,  cities: [] },
      Oceania:    { name: "大洋洲", base: 4655200,    cities: [] },
      Antarctica: { name: "南极洲", base: 500,       cities: [] }
    };
    const CONTINENT_NAME_COLOR = { "亚洲": "#e0a020", "欧洲": "#6fa8dc", "非洲": "#b06a3a", "美洲": "#58a858", "大洋洲": "#b4659e", "南极洲": "#d8dce0" };
    const CONTINENT_COLOR_TO_KEY = { "#e0a020": "Asia", "#6fa8dc": "Europe", "#b06a3a": "Africa", "#58a858": "Americas", "#b4659e": "Oceania", "#d8dce0": "Antarctica" };
    const OCEAN_COLOR = "#0a2a44";

    const CITY_DEFS = [
      { name: "北京", lat: 39.9, lon: 116.4, continent: "Asia", fixed: 30000000 },
      { name: "首尔", lat: 37.57, lon: 126.98, continent: "Asia", fixed: 1400000 },
      { name: "重庆", lat: 29.56, lon: 106.55, continent: "Asia", fixed: 10000000 },
      { name: "丹东", lat: 40.13, lon: 124.39, continent: "Asia", fixed: 400000 },
      { name: "万象", lat: 17.97, lon: 102.63, continent: "Asia", fixed: 600000 },
      { name: "曼谷", lat: 13.76, lon: 100.50, continent: "Asia", fixed: 1000000 },
      { name: "德里", lat: 28.61, lon: 77.21, continent: "Asia", fixed: 6000000 },
      { name: "孟买", lat: 19.1, lon: 72.9, continent: "Asia", min: 8000000, max: 12000000 },
      { name: "东京", lat: 35.7, lon: 139.7, continent: "Asia", min: 8000000, max: 12000000 },
      { name: "巴黎", lat: 48.9, lon: 2.35, continent: "Europe", fixed: 10000000 },
      { name: "格拉斯哥", lat: 55.86, lon: -4.25, continent: "Europe", fixed: 1000000 },
      { name: "马赛", lat: 43.30, lon: 5.37, continent: "Europe", fixed: 300000 },
      { name: "柏林", lat: 52.5, lon: 13.4, continent: "Europe", min: 6000000, max: 12000000 },
      { name: "莫斯科", lat: 55.8, lon: 37.6, continent: "Europe", min: 6000000, max: 12000000 },
      { name: "开罗", lat: 30.0, lon: 31.2, continent: "Africa", fixed: 9000000 },
      { name: "约翰内斯堡", lat: -26.20, lon: 28.05, continent: "Africa", fixed: 4000000 },
      { name: "金沙萨", lat: -4.3, lon: 15.3, continent: "Africa", min: 1800000, max: 8000000 },
      { name: "纽约", lat: 40.7, lon: -74.0, continent: "Americas", fixed: 18000000 },
      { name: "圣弗朗西斯科", lat: 37.77, lon: -122.42, continent: "Americas", fixed: 2000000 },
      { name: "渥太华", lat: 45.42, lon: -75.70, continent: "Americas", fixed: 1050000 },
      { name: "圣保罗", lat: -23.55, lon: -46.63, continent: "Americas", fixed: 1500000 },
      { name: "墨西哥城", lat: 19.4, lon: -99.1, continent: "Americas", min: 4000000, max: 9000000 },
      { name: "里约热内卢", lat: -22.9, lon: -43.2, continent: "Americas", min: 4000000, max: 9000000 },
      { name: "圣地亚哥", lat: -33.5, lon: -70.7, continent: "Americas", min: 4000000, max: 9000000 },
      { name: "悉尼", lat: -33.9, lon: 151.2, continent: "Oceania", fixed: 18000000 },
      { name: "惠灵顿", lat: -41.29, lon: 174.78, continent: "Oceania", fixed: 1400000 },
      { name: "麦克默多站", lat: -77.8, lon: 166.7, continent: "Antarctica", fixed: 500 }
    ];

    const EARTH_STATE = {
      ready: false,
      renderer: null,
      scene: null,
      camera: null,
      globeGroup: null,
      globe: null,
      texCtx: null,
      texW: 2048,
      texH: 1024,
      markers: [],
      labels: [],
      cities: [],
      mode: "none",
      info: null,
      weatherFx: [],
      meteor: null,
      missiles: [],
      conflicts: [],
      conflictPick: null,
      flash: null,
      zoomTargetDist: EARTH_CAMERA_DIST,
      raycaster: null,
      rafId: 0,
      growthTimer: null,
      running: false,
      dragging: false,
      lastDragMove: 0,
      lastFrame: 0
    };

    function randInt(min, max) {
      return Math.floor(Math.random() * (max - min + 1)) + min;
    }

    function buildEarthCities() {
      Object.keys(CONTINENT_DEFS).forEach((key) => { CONTINENT_DEFS[key].cities = []; });
      const list = [];
      CITY_DEFS.forEach((def) => {
        const pop = def.fixed != null ? def.fixed : randInt(def.min, def.max);
        const city = { name: def.name, lat: def.lat, lon: def.lon, continent: def.continent, base: pop, pop, weather: null };
        CONTINENT_DEFS[def.continent].cities.push(city);
        list.push(city);
      });
      return list;
    }

    function latLonToVec3(lat, lon, radius) {
      const phi = (90 - lat) * Math.PI / 180;
      const theta = (lon + 180) * Math.PI / 180;
      return new THREE.Vector3(
        -radius * Math.sin(phi) * Math.cos(theta),
        radius * Math.cos(phi),
        radius * Math.sin(phi) * Math.sin(theta)
      );
    }

    function buildEarthTexture() {
      const W = EARTH_STATE.texW, H = EARTH_STATE.texH;
      const canvas = document.createElement("canvas");
      canvas.width = W;
      canvas.height = H;
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = OCEAN_COLOR;
      ctx.fillRect(0, 0, W, H);
      Object.keys(CONTINENT_DATA).forEach((name) => {
        const color = CONTINENT_NAME_COLOR[name];
        ctx.fillStyle = color;
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        CONTINENT_DATA[name].forEach((ring) => {
          ctx.beginPath();
          let prevLon = null;
          let started = false;
          let crossedSeam = false;
          ring.forEach(([lon, lat]) => {
            const x = (lon + 180) / 360 * W;
            const y = (90 - lat) / 180 * H;
            if (prevLon !== null && Math.abs(lon - prevLon) > 180) {
              ctx.closePath();
              started = false;
              crossedSeam = true;
            }
            if (!started) { ctx.moveTo(x, y); started = true; }
            else ctx.lineTo(x, y);
            prevLon = lon;
          });
          ctx.closePath();
          if (!crossedSeam) ctx.stroke();
          ctx.fill("evenodd");
        });
      });
      return canvas;
    }

    function initEarthScene() {
      if (typeof THREE === "undefined" || !window.CONTINENT_DATA) {
        ui.earthStage.replaceChildren();
        const missing = document.createElement("div");
        missing.className = "earth-stage-missing";
        missing.textContent = "3D 引擎或地图数据未加载（请使用包含 /vendor 资源的最新服务器包）";
        ui.earthStage.appendChild(missing);
        return;
      }
      if (EARTH_STATE.ready) return;
      let renderer;
      try {
        renderer = new THREE.WebGLRenderer({ antialias: true });
      } catch (error) {
        ui.earthStage.replaceChildren();
        const missing = document.createElement("div");
        missing.className = "earth-stage-missing";
        missing.textContent = "当前浏览器不支持 WebGL，无法运行 3D 地球";
        ui.earthStage.appendChild(missing);
        return;
      }
      try {
        initEarthSceneCore(renderer);
      } catch (error) {
        EARTH_STATE.ready = false;
        ui.earthStage.replaceChildren();
        const missing = document.createElement("div");
        missing.className = "earth-stage-missing";
        missing.textContent = "3D 地球初始化失败：" + (error && error.message ? error.message : "未知错误");
        ui.earthStage.appendChild(missing);
      }
    }

    function randomSphereDirection() {
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      return new THREE.Vector3(
        Math.sin(phi) * Math.cos(theta),
        Math.sin(phi) * Math.sin(theta),
        Math.cos(phi)
      );
    }

    function initEarthSceneCore(renderer) {
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      ui.earthStage.appendChild(renderer.domElement);
      const scene = new THREE.Scene();
      scene.background = new THREE.Color(0x050810);
      const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
      camera.position.set(0, 0.25, 3.1);
      camera.lookAt(0, 0, 0);

      const texCanvas = buildEarthTexture();
      const texture = new THREE.CanvasTexture(texCanvas);
      texture.anisotropy = Math.min(renderer.capabilities.getMaxAnisotropy(), 8);
      const globe = new THREE.Mesh(
        new THREE.SphereGeometry(EARTH_RADIUS, 64, 64),
        new THREE.MeshPhongMaterial({ map: texture, specular: 0x222222, shininess: 12 })
      );
      const globeGroup = new THREE.Group();
      globeGroup.add(globe);
      const atmosphere = new THREE.Mesh(
        new THREE.SphereGeometry(EARTH_RADIUS * 1.03, 48, 48),
        new THREE.MeshBasicMaterial({ color: 0x7fb2e5, transparent: true, opacity: 0.13, side: THREE.BackSide })
      );
      globeGroup.add(atmosphere);
      scene.add(globeGroup);
      scene.add(new THREE.AmbientLight(0x556677, 0.9));
      const sun = new THREE.DirectionalLight(0xffffff, 1.15);
      sun.position.set(5, 3, 4);
      scene.add(sun);

      const starGeo = new THREE.BufferGeometry();
      const starPos = new Float32Array(600 * 3);
      for (let i = 0; i < 600; i++) {
        const v = randomSphereDirection().multiplyScalar(20 + Math.random() * 20);
        starPos[i * 3] = v.x;
        starPos[i * 3 + 1] = v.y;
        starPos[i * 3 + 2] = v.z;
      }
      starGeo.setAttribute("position", new THREE.BufferAttribute(starPos, 3));
      scene.add(new THREE.Points(starGeo, new THREE.PointsMaterial({ color: 0xffffff, size: 0.14, sizeAttenuation: false, transparent: true, opacity: 0.85 })));

      EARTH_STATE.renderer = renderer;
      EARTH_STATE.scene = scene;
      EARTH_STATE.camera = camera;
      EARTH_STATE.globeGroup = globeGroup;
      EARTH_STATE.globe = globe;
      EARTH_STATE.texCtx = texCanvas.getContext("2d");
      EARTH_STATE.raycaster = new THREE.Raycaster();
      EARTH_STATE.ready = true;
      resizeEarth();
      bindEarthPointer();
      window.addEventListener("resize", resizeEarth);
    }

    function makeMarkerTexture(color) {
      const size = 64;
      const c = document.createElement("canvas");
      c.width = c.height = size;
      const g = c.getContext("2d");
      g.beginPath();
      g.arc(size / 2, size / 2, size * 0.34, 0, Math.PI * 2);
      g.fillStyle = color;
      g.fill();
      g.lineWidth = 7;
      g.strokeStyle = "rgba(255,255,255,.9)";
      g.stroke();
      return new THREE.CanvasTexture(c);
    }

    const MARKER_TEXTURE_ALIVE = makeMarkerTexture("#ffe14d");
    const MARKER_TEXTURE_DEAD = makeMarkerTexture("#8a8f94");

    function makeMarkerSprite(texture) {
      const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true }));
      sprite.scale.set(0.055, 0.055, 1);
      return sprite;
    }

    function buildEarthMarkers() {
      EARTH_STATE.markers.forEach((m) => EARTH_STATE.globeGroup.remove(m.sprite));
      EARTH_STATE.markers = [];
      EARTH_STATE.cities.forEach((city) => {
        const sprite = makeMarkerSprite(MARKER_TEXTURE_ALIVE);
        sprite.position.copy(latLonToVec3(city.lat, city.lon, EARTH_RADIUS * 1.02));
        EARTH_STATE.globeGroup.add(sprite);
        EARTH_STATE.markers.push({ city, sprite, dead: false });
      });
    }

    // 城市人口归零 -> 死城，标记变灰
    function updateEarthMarkers() {
      EARTH_STATE.markers.forEach((m) => {
        const dead = m.city.pop <= 0;
        if (dead !== m.dead) {
          m.dead = dead;
          m.sprite.material.map = dead ? MARKER_TEXTURE_DEAD : MARKER_TEXTURE_ALIVE;
          m.sprite.material.needsUpdate = true;
        }
      });
    }

    function continentPopulation(key) {
      const def = CONTINENT_DEFS[key];
      let sum = def.base;
      def.cities.forEach((c) => { sum += c.pop - c.base; });
      return sum;
    }

    function rgbToColorKey(r, g, b) {
      const hex = "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("");
      return CONTINENT_COLOR_TO_KEY[hex] || null;
    }

    function continentAtUV(u, v) {
      const ctx = EARTH_STATE.texCtx;
      const W = EARTH_STATE.texW, H = EARTH_STATE.texH;
      const x = Math.floor(u * W) % W;
      const y = Math.min(H - 1, Math.max(0, Math.floor(v * H)));
      const counts = {};
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const d = ctx.getImageData((x + dx + W) % W, Math.min(H - 1, Math.max(0, y + dy)), 1, 1).data;
          const key = rgbToColorKey(d[0], d[1], d[2]);
          if (key) counts[key] = (counts[key] || 0) + 1;
        }
      }
      let best = null, bestN = 0;
      Object.keys(counts).forEach((k) => { if (counts[k] > bestN) { bestN = counts[k]; best = k; } });
      return best;
    }

    function markerAtScreen(px, py, surfaceDist) {
      const w = EARTH_STATE.renderer.domElement.clientWidth;
      const h = EARTH_STATE.renderer.domElement.clientHeight;
      const worldV = new THREE.Vector3();
      const ndc = new THREE.Vector3();
      let best = null, bestD = 18 * 18;
      EARTH_STATE.markers.forEach((m) => {
        // 用世界坐标做距离与投影（球组带旋转，局部坐标投影会错位）
        m.sprite.getWorldPosition(worldV);
        // 被地球挡住的城市不参与命中（世界坐标距离才有效）
        if (worldV.distanceTo(EARTH_STATE.camera.position) > surfaceDist) return;
        ndc.copy(worldV).project(EARTH_STATE.camera);
        if (ndc.z > 1 || ndc.z < -1) return;
        const sx = (ndc.x + 1) / 2 * w;
        const sy = (1 - ndc.y) / 2 * h;
        const dx = sx - px, dy = sy - py;
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = m.city; }
      });
      return best;
    }

    // 大洲标签：中心大圆点 + 大洲名，点击可选中大洲
    const CONTINENT_LABEL_POS = {
      Asia:       { lat: 40, lon: 105 },
      Europe:     { lat: 47, lon: 11 },
      Africa:     { lat: 5, lon: 20 },
      Americas:   { lat: 30, lon: -95 },
      Oceania:    { lat: -25, lon: 134 },
      Antarctica: { lat: -80, lon: 0 }
    };

    function makeContinentLabelTexture(key) {
      const W = 260, H = 96;
      const c = document.createElement("canvas");
      c.width = W;
      c.height = H;
      const g = c.getContext("2d");
      g.beginPath();
      g.arc(46, 48, 34, 0, Math.PI * 2);
      g.fillStyle = CONTINENT_NAME_COLOR[CONTINENT_DEFS[key].name];
      g.fill();
      g.lineWidth = 5;
      g.strokeStyle = "rgba(255,255,255,.92)";
      g.stroke();
      g.font = "bold 40px 'Microsoft YaHei UI','PingFang SC',sans-serif";
      g.textAlign = "left";
      g.textBaseline = "middle";
      g.lineWidth = 6;
      g.strokeStyle = "rgba(8,12,16,.88)";
      g.strokeText(CONTINENT_DEFS[key].name, 94, 50);
      g.fillStyle = "#ffffff";
      g.fillText(CONTINENT_DEFS[key].name, 94, 50);
      return new THREE.CanvasTexture(c);
    }

    const CONTINENT_LABEL_TEXTURES = {};
    Object.keys(CONTINENT_DEFS).forEach((key) => {
      CONTINENT_LABEL_TEXTURES[key] = makeContinentLabelTexture(key);
    });

    function buildContinentLabels() {
      EARTH_STATE.labels.forEach((l) => EARTH_STATE.globeGroup.remove(l.sprite));
      EARTH_STATE.labels = [];
      Object.keys(CONTINENT_DEFS).forEach((key) => {
        const pos = CONTINENT_LABEL_POS[key];
        const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: CONTINENT_LABEL_TEXTURES[key], transparent: true, depthTest: true }));
        sprite.position.copy(latLonToVec3(pos.lat, pos.lon, EARTH_RADIUS * 1.03));
        sprite.scale.set(0.36, 0.133, 1);
        EARTH_STATE.globeGroup.add(sprite);
        EARTH_STATE.labels.push({ key, sprite });
      });
    }

    function continentLabelAtScreen(px, py, surfaceDist) {
      const w = EARTH_STATE.renderer.domElement.clientWidth;
      const h = EARTH_STATE.renderer.domElement.clientHeight;
      const worldV = new THREE.Vector3();
      const ndc = new THREE.Vector3();
      let best = null, bestD = 34 * 34;
      EARTH_STATE.labels.forEach((l) => {
        l.sprite.getWorldPosition(worldV);
        if (worldV.distanceTo(EARTH_STATE.camera.position) > surfaceDist) return;
        ndc.copy(worldV).project(EARTH_STATE.camera);
        if (ndc.z > 1 || ndc.z < -1) return;
        const sx = (ndc.x + 1) / 2 * w;
        const sy = (1 - ndc.y) / 2 * h;
        const dx = sx - px, dy = sy - py;
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = l.key; }
      });
      return best;
    }

    function handleEarthClick(px, py) {
      if (!EARTH_STATE.ready) return;
      const w = EARTH_STATE.renderer.domElement.clientWidth;
      const h = EARTH_STATE.renderer.domElement.clientHeight;
      const ndc = new THREE.Vector2(px / w * 2 - 1, -(py / h * 2 - 1));
      EARTH_STATE.raycaster.setFromCamera(ndc, EARTH_STATE.camera);
      const hits = EARTH_STATE.raycaster.intersectObject(EARTH_STATE.globe);
      const surfaceDist = hits.length ? hits[0].distance : Infinity;
      const city = markerAtScreen(px, py, surfaceDist);
      if (city) {
        if (EARTH_STATE.mode === "meteor") { launchMeteor(city); return; }
        if (EARTH_STATE.mode === "conflict") {
          showToast("⚔ 冲突模式请点击大洲选择阵营", "info", 2200);
          return;
        }
        EARTH_STATE.info = { type: "city", ref: city };
        if (EARTH_STATE.mode === "rain") applyWeather(city, "rain");
        else if (EARTH_STATE.mode === "snow") applyWeather(city, "snow");
        renderEarthInfo();
        return;
      }
      const labelKey = continentLabelAtScreen(px, py, surfaceDist);
      if (labelKey) {
        handleContinentClick(labelKey);
        return;
      }
      if (!hits.length) return;
      const key = continentAtUV(hits[0].uv.x, hits[0].uv.y);
      if (!key) return;
      handleContinentClick(key);
    }

    function handleContinentClick(key) {
      if (EARTH_STATE.mode === "meteor") {
        const cities = CONTINENT_DEFS[key].cities.filter((c) => c.pop > 0);
        if (!cities.length) {
          showToast("☄ " + CONTINENT_DEFS[key].name + " 已是无人区，没有可打击的城市", "info", 2500);
          return;
        }
        const target = cities[Math.floor(Math.random() * cities.length)];
        launchMeteor(target);
        return;
      }
      if (EARTH_STATE.mode === "conflict") {
        handleConflictPick(key);
        return;
      }
      EARTH_STATE.info = { type: "continent", ref: key };
      renderEarthInfo();
    }

    function handleConflictPick(key) {
      if (EARTH_STATE.conflicts.length >= CONFLICT_MAX) {
        showToast("⚔ 最多同时 " + CONFLICT_MAX + " 组冲突，请等待现有冲突结束", "info", 3000);
        return;
      }
      if (!EARTH_STATE.conflictPick) {
        EARTH_STATE.conflictPick = key;
        ui.earthModeTip.textContent = "已选择 " + CONTINENT_DEFS[key].name + "，请再点击一个大洲作为对手";
        return;
      }
      if (EARTH_STATE.conflictPick === key) {
        showToast("请选择另一个大洲作为对手", "info", 2200);
        return;
      }
      createConflict(EARTH_STATE.conflictPick, key);
      EARTH_STATE.conflictPick = null;
      setEarthMode("none");
      showToast("⚔ " + CONTINENT_DEFS[EARTH_STATE.conflicts[EARTH_STATE.conflicts.length - 1].a].name
        + " 与 " + CONTINENT_DEFS[EARTH_STATE.conflicts[EARTH_STATE.conflicts.length - 1].b].name
        + " 爆发冲突（持续 20 分钟）", "info", 3500);
    }

    function createConflict(keyA, keyB) {
      const now = Date.now();
      EARTH_STATE.conflicts.push({
        a: keyA,
        b: keyB,
        started: now,
        nextA: now + randInt(1000, 5000),
        nextB: now + randInt(1000, 5000)
      });
    }

    // 大洲 A 向大洲 B 发射导弹（随机城市 -> 随机城市）
    function launchMissile(fromKey, toKey) {
      if (EARTH_STATE.missiles.length >= 16) return;
      const fromCities = CONTINENT_DEFS[fromKey].cities.filter((c) => c.pop > 0);
      const toCities = CONTINENT_DEFS[toKey].cities.filter((c) => c.pop > 0);
      if (!fromCities.length || !toCities.length) return; // 无城市可发射或可打击
      const from = fromCities[Math.floor(Math.random() * fromCities.length)];
      const to = toCities[Math.floor(Math.random() * toCities.length)];
      const start = latLonToVec3(from.lat, from.lon, EARTH_RADIUS * 1.03);
      const target = latLonToVec3(to.lat, to.lon, EARTH_RADIUS * 1.03);
      // 导弹速度为陨石速度的 1/3（陨石约 1.55 单位/秒）
      const MISSILE_SPEED = 1.55 / 3;
      const distance = start.distanceTo(target);
      const duration = Math.max(1000, distance / MISSILE_SPEED);
      const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(0.032, 8, 8),
        new THREE.MeshBasicMaterial({ color: 0xff5533 })
      );
      mesh.position.copy(start);
      EARTH_STATE.scene.add(mesh);
      // 尾线：固定像素大小，拉远镜头时依然清晰可见
      const trail = new THREE.Points(
        new THREE.BufferGeometry(),
        new THREE.PointsMaterial({ color: 0xff8855, size: 7, sizeAttenuation: false, transparent: true, opacity: 0.8, depthWrite: false })
      );
      EARTH_STATE.scene.add(trail);
      EARTH_STATE.missiles.push({ mesh, trail, start: start.clone(), target, t: 0, duration, from, to, trailPos: [] });
    }

    function updateMissiles(dt) {
      for (let i = EARTH_STATE.missiles.length - 1; i >= 0; i--) {
        const m = EARTH_STATE.missiles[i];
        m.t += dt;
        const k = Math.min(1, m.t / m.duration);
        const kk = k * k;
        // 沿弦线运动，并向外拱起（弹道）；起点/终点精确落在城市
        const bulge = Math.sin(Math.PI * Math.min(1, k)) * 0.16;
        const out = m.start.clone().add(m.target).normalize().multiplyScalar(bulge);
        m.mesh.position.lerpVectors(m.start, m.target, kk).add(out);
        m.trailPos.push(m.mesh.position.clone());
        if (m.trailPos.length > 42) m.trailPos.shift();
        const tPos = new Float32Array(m.trailPos.length * 3);
        m.trailPos.forEach((p, j) => { tPos[j * 3] = p.x; tPos[j * 3 + 1] = p.y; tPos[j * 3 + 2] = p.z; });
        m.trail.geometry.setAttribute("position", new THREE.BufferAttribute(tPos, 3));
        if (k >= 1) {
          EARTH_STATE.scene.remove(m.mesh);
          EARTH_STATE.scene.remove(m.trail);
          m.mesh.geometry.dispose();
          m.mesh.material.dispose();
          m.trail.geometry.dispose();
          m.trail.material.dispose();
          const loss = randInt(MISSILE_LOSS_MIN, MISSILE_LOSS_MAX);
          m.to.pop -= loss;
          impactFlash(m.target);
          if (m.to.pop <= 0) updateEarthMarkers();
          showToast("🚀 " + m.from.name + " → " + m.to.name + "（-" + loss.toLocaleString("zh-CN") + "）", "info", 2000);
          if (EARTH_STATE.info && EARTH_STATE.info.ref === m.to) renderEarthInfo();
          EARTH_STATE.missiles.splice(i, 1);
        }
      }
    }

    // 冲突推进：每侧每 1~5 秒发射一次，20 分钟后结束
    function updateConflicts() {
      const now = Date.now();
      EARTH_STATE.conflicts = EARTH_STATE.conflicts.filter((c) => {
        if (now - c.started >= CONFLICT_DURATION_MS) {
          showToast("⚔ " + CONTINENT_DEFS[c.a].name + " 与 " + CONTINENT_DEFS[c.b].name + " 的冲突已结束", "info", 3000);
          return false;
        }
        if (now >= c.nextA) { launchMissile(c.a, c.b); c.nextA = now + randInt(1000, 5000); }
        if (now >= c.nextB) { launchMissile(c.b, c.a); c.nextB = now + randInt(1000, 5000); }
        return true;
      });
      renderEarthConflicts();
    }

    function renderEarthConflicts() {
      const el = ui.earthConflicts;
      el.replaceChildren();
      if (!EARTH_STATE.conflicts.length) {
        el.hidden = true;
        return;
      }
      el.hidden = false;
      EARTH_STATE.conflicts.forEach((c) => {
        const remaining = Math.max(0, CONFLICT_DURATION_MS - (Date.now() - c.started));
        const mm = Math.floor(remaining / 60000);
        const ss = Math.floor((remaining % 60000) / 1000);
        const item = document.createElement("div");
        item.className = "earth-conflict-item";
        item.textContent = "⚔ " + CONTINENT_DEFS[c.a].name + " ⟷ " + CONTINENT_DEFS[c.b].name
          + "（剩余 " + mm + ":" + String(ss).padStart(2, "0") + "）";
        el.appendChild(item);
      });
    }

    function renderEarthInfo() {
      const el = ui.earthInfo;
      el.replaceChildren();
      const info = EARTH_STATE.info;
      if (!info) {
        const d = document.createElement("div");
        d.className = "earth-info-empty";
        d.textContent = "点击大洲或城市查看信息";
        el.appendChild(d);
        return;
      }
      if (info.type === "continent") {
        const def = CONTINENT_DEFS[info.ref];
        const pop = continentPopulation(info.ref);
        const dead = pop <= 0;
        const title = document.createElement("div");
        title.className = "earth-info-title";
        title.textContent = (dead ? "☠ " : "🏔 ") + def.name;
        el.appendChild(title);
        addInfoRow(el, "人口", (dead ? 0 : pop).toLocaleString("zh-CN"));
        addInfoRow(el, "状态", dead ? "☠ 无人区" : "正常");
        addInfoRow(el, "城市数", def.cities.length + " 个");
        def.cities.forEach((city) => addInfoRow(el, "· " + city.name, (city.pop <= 0 ? "☠ " : "") + Math.max(0, city.pop).toLocaleString("zh-CN")));
      } else {
        const city = info.ref;
        const dead = city.pop <= 0;
        const title = document.createElement("div");
        title.className = "earth-info-title";
        title.textContent = (dead ? "☠ " : "🏙 ") + city.name;
        el.appendChild(title);
        addInfoRow(el, "人口", (dead ? 0 : city.pop).toLocaleString("zh-CN"));
        addInfoRow(el, "所属大洲", CONTINENT_DEFS[city.continent].name);
        addInfoRow(el, "天气", city.weather === "rain" ? "🌧 下雨" : city.weather === "snow" ? "❄ 下雪" : "无");
        addInfoRow(el, "状态", dead ? "☠ 死城" : "正常");
      }
    }

    function addInfoRow(el, label, value) {
      const row = document.createElement("div");
      row.className = "earth-info-row";
      const l = document.createElement("span");
      l.className = "label";
      l.textContent = label;
      const v = document.createElement("span");
      v.className = "value";
      v.textContent = value;
      row.append(l, v);
      el.appendChild(row);
    }

    function applyWeather(city, type) {
      city.weather = type;
      removeWeatherFx(city);
      const n = 130;
      const cityVec = latLonToVec3(city.lat, city.lon, EARTH_RADIUS);
      let up = new THREE.Vector3(0, 1, 0);
      if (Math.abs(cityVec.dot(up)) > 0.98) up = new THREE.Vector3(1, 0, 0);
      const u = new THREE.Vector3().crossVectors(cityVec, up).normalize();
      const v = new THREE.Vector3().crossVectors(cityVec, u).normalize();
      const fx = { city, type, points: null, cityVec, u, v, dir: new Float32Array(n * 2), seeds: new Float32Array(n), speeds: new Float32Array(n), phase: new Float32Array(n), count: n };
      for (let i = 0; i < n; i++) {
        fx.dir[i * 2] = (Math.random() - 0.5) * 0.5;
        fx.dir[i * 2 + 1] = (Math.random() - 0.5) * 0.5;
        fx.seeds[i] = 0.015 + Math.random() * 0.13;
        fx.speeds[i] = type === "rain" ? 0.09 + Math.random() * 0.09 : 0.03 + Math.random() * 0.03;
        fx.phase[i] = Math.random();
      }
      const material = new THREE.PointsMaterial({
        color: type === "rain" ? 0x7fc4ff : 0xffffff,
        size: type === "rain" ? 0.007 : 0.013,
        transparent: true,
        opacity: 0.95,
        depthWrite: false
      });
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(n * 3), 3));
      fx.points = new THREE.Points(geometry, material);
      EARTH_STATE.scene.add(fx.points);
      EARTH_STATE.weatherFx.push(fx);
    }

    function removeWeatherFx(city) {
      EARTH_STATE.weatherFx = EARTH_STATE.weatherFx.filter((fx) => {
        if (fx.city === city) {
          EARTH_STATE.scene.remove(fx.points);
          fx.points.geometry.dispose();
          fx.points.material.dispose();
          return false;
        }
        return true;
      });
    }

    function updateWeatherFx(dt) {
      EARTH_STATE.weatherFx.forEach((fx) => {
        const attr = fx.points.geometry.attributes.position;
        const pos = attr.array;
        for (let i = 0; i < fx.count; i++) {
          fx.phase[i] = (fx.phase[i] + fx.speeds[i] * dt * 0.06) % 1;
          const h = fx.seeds[i] * (1 - fx.phase[i]);
          const px = fx.cityVec.x + fx.u.x * fx.dir[i * 2] + fx.v.x * fx.dir[i * 2 + 1];
          const py = fx.cityVec.y + fx.u.y * fx.dir[i * 2] + fx.v.y * fx.dir[i * 2 + 1];
          const pz = fx.cityVec.z + fx.u.z * fx.dir[i * 2] + fx.v.z * fx.dir[i * 2 + 1];
          const len = Math.sqrt(px * px + py * py + pz * pz) || 1;
          const r = EARTH_RADIUS + h;
          pos[i * 3] = px / len * r;
          pos[i * 3 + 1] = py / len * r;
          pos[i * 3 + 2] = pz / len * r;
        }
        attr.needsUpdate = true;
      });
    }

    function launchMeteor(city) {
      if (EARTH_STATE.meteor) return;
      const target = latLonToVec3(city.lat, city.lon, EARTH_RADIUS);
      // 起点在 1.8 倍半径处（屏幕内可见），缓慢坠落约 1.5 秒
      const start = target.clone().multiplyScalar(1.8)
        .add(new THREE.Vector3((Math.random() - .5) * .5, (Math.random() - .5) * .5, (Math.random() - .5) * .5));
      const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(0.06, 10, 10),
        new THREE.MeshBasicMaterial({ color: 0xffa040 })
      );
      mesh.position.copy(start);
      EARTH_STATE.scene.add(mesh);
      // 尾迹：固定像素大小，拉远镜头时依然清晰可见
      const trail = new THREE.Points(
        new THREE.BufferGeometry(),
        new THREE.PointsMaterial({ color: 0xff7a20, size: 11, sizeAttenuation: false, transparent: true, opacity: 0.85, depthWrite: false })
      );
      EARTH_STATE.scene.add(trail);
      EARTH_STATE.meteor = { city, mesh, trail, start: start.clone(), target, t: 0, duration: 1500, trailPos: [] };
      ui.earthModeTip.textContent = "☄ 陨石正飞向 " + city.name + " ...";
    }

    function impactFlash(position) {
      if (EARTH_STATE.flash) EARTH_STATE.scene.remove(EARTH_STATE.flash.sprite);
      const c = document.createElement("canvas");
      c.width = c.height = 64;
      const g = c.getContext("2d");
      const grad = g.createRadialGradient(32, 32, 2, 32, 32, 30);
      grad.addColorStop(0, "rgba(255,205,130,1)");
      grad.addColorStop(1, "rgba(255,120,40,0)");
      g.fillStyle = grad;
      g.fillRect(0, 0, 64, 64);
      const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false }));
      sprite.position.copy(position);
      sprite.scale.set(0.22, 0.22, 1);
      EARTH_STATE.scene.add(sprite);
      EARTH_STATE.flash = { sprite, t: 0 };
    }

    function updateFlash(dt) {
      const f = EARTH_STATE.flash;
      if (!f) return;
      f.t += dt;
      const k = f.t / 500;
      f.sprite.material.opacity = Math.max(0, 1 - k);
      f.sprite.scale.setScalar(0.22 + k * 0.55);
      if (k >= 1) {
        EARTH_STATE.scene.remove(f.sprite);
        EARTH_STATE.flash = null;
      }
    }

    function updateMeteor(dt) {
      const m = EARTH_STATE.meteor;
      if (!m) return;
      m.t += dt;
      const k = Math.min(1, m.t / m.duration);
      m.mesh.position.lerpVectors(m.start, m.target, k * k);
      m.trailPos.push(m.mesh.position.clone());
      if (m.trailPos.length > 22) m.trailPos.shift();
      const tPos = new Float32Array(m.trailPos.length * 3);
      m.trailPos.forEach((p, i) => { tPos[i * 3] = p.x; tPos[i * 3 + 1] = p.y; tPos[i * 3 + 2] = p.z; });
      m.trail.geometry.setAttribute("position", new THREE.BufferAttribute(tPos, 3));
      if (k >= 1) {
        EARTH_STATE.scene.remove(m.mesh);
        EARTH_STATE.scene.remove(m.trail);
        m.mesh.geometry.dispose();
        m.mesh.material.dispose();
        m.trail.geometry.dispose();
        m.trail.material.dispose();
        const loss = randInt(800000, 1500000);
        m.city.pop -= loss;
        if (m.city.pop <= 0) updateEarthMarkers();
        impactFlash(m.target);
        showToast("☄ 陨石击中 " + m.city.name + "！人口减少 " + loss.toLocaleString("zh-CN"), "info", 4000);
        EARTH_STATE.meteor = null;
        ui.earthModeTip.textContent = "☄ 陨石模式：点击一个大洲，随机一个城市被陨石击中";
        if (EARTH_STATE.info && EARTH_STATE.info.ref === m.city) renderEarthInfo();
      }
    }

    function resizeEarth() {
      if (!EARTH_STATE.ready) return;
      const stage = ui.earthStage;
      const w = Math.max(1, stage.clientWidth);
      const h = Math.max(1, stage.clientHeight);
      EARTH_STATE.renderer.setSize(w, h, false);
      EARTH_STATE.camera.aspect = w / h;
      EARTH_STATE.camera.updateProjectionMatrix();
    }

    function bindEarthPointer() {
      const canvas = EARTH_STATE.renderer.domElement;
      let drag = null;
      canvas.addEventListener("pointerdown", (e) => {
        drag = { x: e.clientX, y: e.clientY, moved: false, t: Date.now() };
        EARTH_STATE.dragging = true;
        canvas.classList.add("dragging");
        try { canvas.setPointerCapture(e.pointerId); } catch (_) { }
      });
      canvas.addEventListener("pointermove", (e) => {
        if (!drag) return;
        const dx = e.clientX - drag.x;
        const dy = e.clientY - drag.y;
        if (Math.abs(dx) + Math.abs(dy) > 6) drag.moved = true;
        if (drag.moved) {
          EARTH_STATE.globeGroup.rotation.y += dx * 0.005;
          EARTH_STATE.globeGroup.rotation.x = Math.max(-1.2, Math.min(1.2, EARTH_STATE.globeGroup.rotation.x + dy * 0.003));
          drag.x = e.clientX;
          drag.y = e.clientY;
          EARTH_STATE.lastDragMove = Date.now();
        }
      });
      const finish = (e) => {
        if (!drag) return;
        const wasClick = !drag.moved && Date.now() - drag.t < 600;
        const rect = canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        drag = null;
        EARTH_STATE.dragging = false;
        canvas.classList.remove("dragging");
        if (wasClick) handleEarthClick(x, y);
      };
      canvas.addEventListener("pointerup", finish);
      canvas.addEventListener("pointercancel", () => { drag = null; EARTH_STATE.dragging = false; canvas.classList.remove("dragging"); });
      canvas.addEventListener("wheel", (e) => {
        e.preventDefault();
        const delta = e.deltaY > 0 ? 1 : -1;
        ui.earthZoom.value = Math.max(0, Math.min(100, Number(ui.earthZoom.value) + delta * 5));
        EARTH_STATE.zoomTargetDist = EARTH_CAMERA_DIST + (Number(ui.earthZoom.value) / 100) * (EARTH_ZOOM_MAX_DIST - EARTH_CAMERA_DIST);
      }, { passive: false });
    }

    function earthAnimate(now) {
      if (!EARTH_STATE.running) return;
      EARTH_STATE.rafId = window.requestAnimationFrame(earthAnimate);
      const dt = Math.min(50, now - (EARTH_STATE.lastFrame || now));
      EARTH_STATE.lastFrame = now;
      if (!EARTH_STATE.dragging && now - EARTH_STATE.lastDragMove > 3000) {
        EARTH_STATE.globeGroup.rotation.y += 0.0008 * dt;
      }
      // 缩放：平滑接近目标距离（最远为当前的 2 倍）
      if (Math.abs(EARTH_STATE.camera.position.length() - EARTH_STATE.zoomTargetDist) > 0.001) {
        const d = EARTH_STATE.camera.position.length();
        const nd = d + (EARTH_STATE.zoomTargetDist - d) * Math.min(1, dt / 160);
        EARTH_STATE.camera.position.setLength(nd);
      }
      // 陨石/导弹模型按相机距离补偿缩放，保证任何缩放下屏幕大小恒定可见
      const zoomScale = EARTH_STATE.camera.position.length() / EARTH_CAMERA_DIST;
      if (EARTH_STATE.meteor) EARTH_STATE.meteor.mesh.scale.setScalar(zoomScale);
      for (let i = 0; i < EARTH_STATE.missiles.length; i++) {
        EARTH_STATE.missiles[i].mesh.scale.setScalar(zoomScale);
      }
      if (EARTH_STATE.meteor) updateMeteor(dt);
      if (EARTH_STATE.missiles.length) updateMissiles(dt);
      if (EARTH_STATE.weatherFx.length) updateWeatherFx(dt);
      if (EARTH_STATE.flash) updateFlash(dt);
      EARTH_STATE.renderer.render(EARTH_STATE.scene, EARTH_STATE.camera);
    }

    function applyEarthGrowthTick() {
      // 所有城市每秒增长 1954~20000；南极洲不增长，只在受攻击时减少
      EARTH_STATE.cities.forEach((city) => {
        if (city.continent !== "Antarctica") city.pop += randInt(1954, 20000);
      });
      updateConflicts();
      updateEarthMarkers();
      if (EARTH_STATE.info) renderEarthInfo();
    }

    function startEarthGrowth() {
      stopEarthGrowth();
      EARTH_STATE.growthTimer = window.setInterval(applyEarthGrowthTick, 1000);
    }

    function stopEarthGrowth() {
      if (EARTH_STATE.growthTimer) {
        window.clearInterval(EARTH_STATE.growthTimer);
        EARTH_STATE.growthTimer = null;
      }
    }

    function setEarthMode(mode) {
      EARTH_STATE.mode = mode;
      if (mode !== "conflict") EARTH_STATE.conflictPick = null;
      const map = {
        none: ui.earthModeNone, rain: ui.earthModeRain, snow: ui.earthModeSnow,
        meteor: ui.earthModeMeteor, conflict: ui.earthModeConflict
      };
      Object.keys(map).forEach((key) => map[key].classList.toggle("active", key === mode));
      const tips = {
        none: "拖拽旋转地球，点击大洲或城市查看人口",
        rain: "🌧 下雨模式：点击一个城市，让那里开始下雨",
        snow: "❄ 下雪模式：点击一个城市，让那里开始下雪",
        meteor: "☄ 陨石模式：点击大洲或城市，陨石砸向目标",
        conflict: "⚔ 冲突模式：依次点击两个大洲，让它们互相发射导弹"
      };
      ui.earthModeTip.textContent = tips[mode];
    }

    function enterEarth() {
      showScreen("earth");
      initEarthScene();
      if (!EARTH_STATE.ready) return;
      // 每次启动游戏都刷新随机人数的城市
      EARTH_STATE.cities = buildEarthCities();
      buildEarthMarkers();
      buildContinentLabels();
      EARTH_STATE.weatherFx.forEach((fx) => EARTH_STATE.scene.remove(fx.points));
      EARTH_STATE.weatherFx = [];
      // 清理未完成的导弹动画（冲突本身继续计时）
      EARTH_STATE.missiles.forEach((m) => {
        EARTH_STATE.scene.remove(m.mesh);
        EARTH_STATE.scene.remove(m.trail);
      });
      EARTH_STATE.missiles = [];
      EARTH_STATE.info = null;
      EARTH_STATE.meteor = null;
      EARTH_STATE.conflictPick = null;
      EARTH_STATE.lastDragMove = 0;
      EARTH_STATE.lastFrame = 0;
      EARTH_STATE.zoomTargetDist = EARTH_CAMERA_DIST + (ui.earthZoom.value / 100) * (EARTH_ZOOM_MAX_DIST - EARTH_CAMERA_DIST);
      renderEarthInfo();
      renderEarthConflicts();
      setEarthMode("none");
      EARTH_STATE.running = true;
      window.requestAnimationFrame(earthAnimate);
      startEarthGrowth();
      resizeEarth();
    }

    function leaveEarth() {
      EARTH_STATE.running = false;
      if (EARTH_STATE.rafId) window.cancelAnimationFrame(EARTH_STATE.rafId);
      EARTH_STATE.rafId = 0;
      stopEarthGrowth();
      showScreen("games");
    }

    // 停止本页所有小游戏进程（返回游戏列表 / 服务器断开 / 重新连接时调用）
    function stopAllGames() {
      fpsStopMpMusic();
      stopSnakeTimer();
      if (snake.game) { snake.game.running = false; snake.game.over = true; }
      stopClickTimer();
      if (clickGame.game) clickGame.game.over = true;
      if (EARTH_STATE.running) {
        EARTH_STATE.running = false;
        if (EARTH_STATE.rafId) window.cancelAnimationFrame(EARTH_STATE.rafId);
        EARTH_STATE.rafId = 0;
        stopEarthGrowth();
        EARTH_STATE.missiles.forEach((mm) => {
          EARTH_STATE.scene.remove(mm.mesh);
          EARTH_STATE.scene.remove(mm.trail);
        });
        EARTH_STATE.missiles = [];
        if (EARTH_STATE.meteor) {
          EARTH_STATE.scene.remove(EARTH_STATE.meteor.mesh);
          EARTH_STATE.scene.remove(EARTH_STATE.meteor.trail);
          EARTH_STATE.meteor = null;
        }
        EARTH_STATE.weatherFx.forEach((fx) => EARTH_STATE.scene.remove(fx.points));
        EARTH_STATE.weatherFx = [];
        EARTH_STATE.info = null;
      }
      if (FPS_STATE.running) {
        stopFpsLoop();
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
           if (FPS_STATE.mp.pickups) {
             if (FPS_STATE.mp.pickups.blood) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.blood);
             if (FPS_STATE.mp.pickups.ammo) FPS_STATE.scene.remove(FPS_STATE.mp.pickups.ammo);
             FPS_STATE.mp.pickups = null;
           }
           ui.fpsScoreWrap.hidden = false;
        }
        FPS_STATE.tracers.forEach((t) => FPS_STATE.scene.remove(t.line));
        FPS_STATE.tracers = [];
        if (document.pointerLockElement && document.exitPointerLock) document.exitPointerLock();
        if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => { });
        setFpsTouchControls(false);
        ui.fpsSelfId.hidden = true;
        ui.fpsRespawnOverlay.hidden = true;
        ui.fpsPause.hidden = true;
        FPS_STATE.paused = false;
        fpsUntrapHistory();
        FPS_STATE.phase = "menu";
        ui.fpsHud.hidden = true;
        ui.fpsSettle.hidden = true;
        ui.fpsMenu.hidden = false;
        ui.fpsMpPanel.hidden = true;
      }
    }


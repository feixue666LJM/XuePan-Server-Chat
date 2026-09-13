(() => {
  "use strict";

  window.FPS_EXTENDED_WEAPONS = window.FPS_EXTENDED_WEAPONS || [];
  window.FPS_EXTENDED_WEAPONS.push({
    id: "m249",
    name: "M249",
    magSize: 180,
    reserve: 360,
    fireRate: 14,
    damageBody: 29,
    damageHead: 92,
    color: 0x33413b,
    muzzleColor: 0x202824,
    speedMultiplier: 0.97,
    botKillReserve: 27,
    playerKillReserve: 0,
    playerKillHeal: 0,
    model: "m249"
  });

  window.FPS_WEAPON_MODELS = window.FPS_WEAPON_MODELS || {};
  window.FPS_WEAPON_MODELS.m249 = {
    create(THREE, weapon) {
      const group = new THREE.Group();
      const bodyMat = new THREE.MeshLambertMaterial({ color: weapon.color });
      const darkMat = new THREE.MeshLambertMaterial({ color: weapon.muzzleColor });
      const beltMat = new THREE.MeshLambertMaterial({ color: weapon.id === "ddr600" ? 0xffd55c : 0x826d3a });
      const add = (geometry, material, x, y, z) => {
        const mesh = new THREE.Mesh(geometry, material);
        mesh.position.set(x, y, z);
        group.add(mesh);
        return mesh;
      };

      const receiver = add(new THREE.BoxGeometry(0.18, 0.2, 0.76), bodyMat, 0, -0.01, -0.3);
      const barrel = add(new THREE.BoxGeometry(0.09, 0.09, 1.12), darkMat, 0, 0.025, -1.15);
      const shroud = add(new THREE.BoxGeometry(0.13, 0.14, 0.38), bodyMat, 0, 0.025, -0.76);
      const stock = add(new THREE.BoxGeometry(0.15, 0.15, 0.44), bodyMat, 0, -0.01, 0.27);
      const grip = add(new THREE.BoxGeometry(0.11, 0.27, 0.12), darkMat, 0, -0.17, -0.01);
      const ammoBox = add(new THREE.BoxGeometry(0.21, 0.28, 0.3), darkMat, 0, -0.19, -0.31);
      add(new THREE.BoxGeometry(0.04, 0.04, 0.5), beltMat, 0.13, -0.13, -0.42);
      const bipodLeft = add(new THREE.BoxGeometry(0.035, 0.24, 0.035), darkMat, -0.12, -0.15, -0.95);
      const bipodRight = add(new THREE.BoxGeometry(0.035, 0.24, 0.035), darkMat, 0.12, -0.15, -0.95);
      bipodLeft.rotation.z = -0.45;
      bipodRight.rotation.z = 0.45;
      const muzzle = add(new THREE.SphereGeometry(0.05, 8, 8), darkMat, 0, 0.025, -1.73);

      const applyAppearance = (nextWeapon) => {
        bodyMat.color.setHex(nextWeapon.color);
        darkMat.color.setHex(nextWeapon.muzzleColor);
        beltMat.color.setHex(nextWeapon.id === "ddr600" ? 0xffd55c : 0x826d3a);
      };
      return {
        group,
        bodyParts: [receiver, shroud, stock],
        muzzle,
        flashPosition: new THREE.Vector3(0, 0.025, -1.81),
        applyAppearance
      };
    }
  };
})();

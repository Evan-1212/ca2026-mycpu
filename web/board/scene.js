// SPDX-License-Identifier: MIT
// The 3D scene of the virtual teaching board, with three.js from
// web/vendor/three/. Every model is built here from boxes, cylinders and text
// drawn on canvases; no model file is loaded. The orbit, pan and zoom controls
// are this file's own. The scene renders on demand, not in a loop.

import { BOARD, COMPONENTS, NETS, PROBES, present } from './model.js';

const NET_OWNER = { 'uart-tx': 'uart', 'uart-rx': 'uart', 'mem-bus': 'ram', 'mem-valid': 'ram', 'vga-hs': 'vga',
  'vga-vs': 'vga', clk: 'clock', rst: 'reset' };
const SNAP = 1.0; // board units within which a dropped probe lands on a net

/** True when the browser gives a WebGL context; the context is released again. */
export function webglAvailable() {
  try {
    const c = document.createElement('canvas');
    const gl = c.getContext('webgl2') || c.getContext('webgl');
    if (!gl) return false;
    const lose = gl.getExtension('WEBGL_lose_context');
    if (lose) lose.loseContext();
    return true;
  } catch {
    return false;
  }
}

function labelCanvas(text, { px = 48, color = '#f2f4f6', bg = null, width = null } = {}) {
  const c = document.createElement('canvas');
  const ctx = c.getContext('2d');
  ctx.font = `600 ${px}px system-ui, sans-serif`;
  const w = Math.ceil(width || ctx.measureText(text).width + px);
  c.width = w;
  c.height = Math.ceil(px * 1.5);
  if (bg) {
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, c.width, c.height);
  }
  ctx.font = `600 ${px}px system-ui, sans-serif`;
  ctx.fillStyle = color;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, c.width / 2, c.height / 2);
  return c;
}

/**
 * Builds the scene in `host` (an element with a size). opts: {task,
 * fieldPresent(field), probePositions {P1: {x, z, net}}, screen (a canvas),
 * onSelect(id), onProbe(probe, net, position), onRendered(hotspots)}.
 */
export async function createScene(host, opts) {
  const THREE = await import('../vendor/three/three.module.js');
  // what has been made so far, so that a failure halfway still releases the WebGL context
  const res = { textures: [], listeners: [], frameRequest: 0, disposed: false };
  try {
    return build(THREE, host, opts, res);
  } catch (e) {
    release(res);
    throw e;
  }
}

function release(res) {
  res.disposed = true;
  if (res.frameRequest) cancelAnimationFrame(res.frameRequest);
  if (res.resizer) res.resizer.disconnect();
  for (const off of res.listeners.splice(0)) off();
  if (res.scene) {
    res.scene.traverse((o) => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) for (const m of [].concat(o.material)) m.dispose();
    });
  }
  for (const t of res.textures) t.dispose();
  if (res.renderer) {
    res.renderer.dispose();
    res.renderer.forceContextLoss();
  }
  if (res.canvas) res.canvas.remove();
}

function build(THREE, host, opts, res) {
  const canvas = document.createElement('canvas');
  res.canvas = canvas;
  canvas.className = 'bd-gl';
  canvas.setAttribute('aria-label', 'virtual teaching board; drag to orbit, right-drag or shift-drag to pan, wheel to zoom, drag a probe onto a pin');
  host.append(canvas);
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  res.renderer = renderer;
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  const scene = new THREE.Scene();
  res.scene = scene;
  scene.background = new THREE.Color(0x20252b);
  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 300);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x334455, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.6);
  sun.position.set(-8, 20, 12);
  scene.add(sun);

  const pickables = [];
  const textures = res.textures;
  const texture = (c) => {
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = 4;
    textures.push(t);
    return t;
  };
  const flatLabel = (text, width, opts2 = {}) => {
    const c = labelCanvas(text, opts2);
    const h = (width * c.height) / c.width;
    const m = new THREE.Mesh(new THREE.PlaneGeometry(width, h),
      new THREE.MeshBasicMaterial({ map: texture(c), transparent: true, depthWrite: false }));
    m.rotation.x = -Math.PI / 2;
    return m;
  };

  // the board and its silkscreen
  const pcb = new THREE.Mesh(new THREE.BoxGeometry(BOARD.width, BOARD.thickness, BOARD.depth),
    new THREE.MeshStandardMaterial({ color: 0x1d6b3a, roughness: 0.8 }));
  pcb.position.y = -BOARD.thickness / 2;
  scene.add(pcb);
  const caption = flatLabel('virtual teaching board, not a physical PCB', 15, { px: 40, color: '#e8f5ea' });
  caption.position.set(0, 0.01, BOARD.depth / 2 - 0.9);
  scene.add(caption);

  // the components
  const groups = new Map();
  for (const c of COMPONENTS) {
    const on = present(c, opts.task);
    const g = new THREE.Group();
    g.userData.id = c.id;
    const [w, h, d] = c.size;
    const material = new THREE.MeshStandardMaterial({ color: c.color, roughness: 0.55, metalness: 0.1,
      transparent: c.kind === 'concept' || !on, opacity: c.kind === 'concept' ? 0.55 : on ? 1 : 0.3 });
    const body = new THREE.Mesh(c.id === 'clock' ? new THREE.CylinderGeometry(w / 2, w / 2, h, 24)
      : c.id === 'reset' ? new THREE.CylinderGeometry(w / 2, w / 2, h, 20) : new THREE.BoxGeometry(w, h, d), material);
    body.position.y = h / 2;
    if (c.id === 'clock') body.scale.z = d / w;
    g.add(body);
    if (c.kind === 'concept') { // concepts get a dashed outline
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(body.geometry),
        new THREE.LineDashedMaterial({ color: 0xffffff, dashSize: 0.2, gapSize: 0.15 }));
      edges.position.copy(body.position);
      edges.scale.copy(body.scale);
      edges.computeLineDistances();
      g.add(edges);
    }
    const text = c.kind === 'concept' ? `${c.label} (concept)` : on ? c.label : `${c.label} (T${c.from} only)`;
    const label = flatLabel(text, Math.max(w, 0.36 * text.length), { px: 44 });
    label.position.set(0, h + 0.02, 0);
    g.add(label);
    if (c.id === 'soc') { // pins along the four edges and a mark on the package
      const pinGeo = new THREE.BoxGeometry(0.18, 0.08, 0.35);
      const pinMat = new THREE.MeshStandardMaterial({ color: 0xc9b26b, metalness: 0.7, roughness: 0.3 });
      for (let i = 0; i < 10; i++) {
        const t = -2 + (i * 4) / 9;
        for (const [x, z, rot] of [[t, -2.65, 0], [t, 2.65, 0], [-2.65, t, 1], [2.65, t, 1]]) {
          const p = new THREE.Mesh(pinGeo, pinMat);
          p.position.set(x, 0.04, z);
          if (rot) p.rotation.y = Math.PI / 2;
          g.add(p);
        }
      }
      const sub = flatLabel('RISC-V core, data port', 4.2, { px: 30, color: '#b8c0c8' });
      sub.position.set(0, h + 0.02, 1.4);
      g.add(sub);
    }
    g.position.set(...c.pos);
    scene.add(g);
    groups.set(c.id, g);
    pickables.push(g);
  }

  // the VGA screen, behind the VGA connector
  const monitor = new THREE.Group();
  monitor.userData.id = 'vga';
  const frame = new THREE.Mesh(new THREE.BoxGeometry(6.6, 5.2, 0.4), new THREE.MeshStandardMaterial({ color: 0x15181c }));
  monitor.add(frame);
  const screenMat = new THREE.MeshBasicMaterial({ map: texture(opts.screen) });
  const screen = new THREE.Mesh(new THREE.PlaneGeometry(6, 4.5), screenMat);
  screen.position.z = 0.21;
  monitor.add(screen);
  const stand = new THREE.Mesh(new THREE.BoxGeometry(0.5, 1.6, 0.5), new THREE.MeshStandardMaterial({ color: 0x15181c }));
  stand.position.y = -3.4;
  monitor.add(stand);
  monitor.position.set(-8.5, 4.2, -10.2);
  scene.add(monitor);
  pickables.push(monitor);

  // nets: a trace from the SoC to each pin, and the pin a probe lands on
  const netMeshes = new Map();
  const traceMat = new THREE.MeshStandardMaterial({ color: 0xc9a227, metalness: 0.6, roughness: 0.4 });
  const conceptTraceMat = new THREE.MeshStandardMaterial({ color: 0x9aa4ae, transparent: true, opacity: 0.6 });
  for (const n of NETS) {
    const recorded = n.field !== null && opts.fieldPresent(n.field);
    const [x, , z] = n.pos;
    const edgeX = x < 0 ? -2.5 : 2.5;
    const sx = Math.abs(x - edgeX);
    const isConcept = n.field === null;
    if (sx > 0.2) {
      const tr = new THREE.Mesh(new THREE.BoxGeometry(sx, 0.03, 0.12), isConcept ? conceptTraceMat : traceMat);
      tr.position.set((x + edgeX) / 2, 0.015, Math.max(-2.3, Math.min(2.3, z)));
      scene.add(tr);
    }
    if (Math.abs(z) > 2.3) { // the part of the trace that runs along z, past the SoC's edge
      const dz = Math.abs(z) - 2.3;
      const tz = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.03, dz), isConcept ? conceptTraceMat : traceMat);
      tz.position.set(x, 0.015, Math.sign(z) * (2.3 + dz / 2));
      scene.add(tz);
    }
    const pin = new THREE.Mesh(new THREE.CylinderGeometry(0.28, 0.28, 0.25, 16),
      new THREE.MeshStandardMaterial({ color: recorded ? 0xf0c040 : 0x6b7178, metalness: 0.5, roughness: 0.4 }));
    pin.position.set(x, 0.125, z);
    pin.userData.net = n.id;
    scene.add(pin);
    netMeshes.set(n.id, pin);
    pickables.push(pin);
  }

  // the probes
  const probes = new Map();
  for (const p of PROBES) {
    const g = new THREE.Group();
    g.userData.probe = p.id;
    const mat = new THREE.MeshStandardMaterial({ color: p.color, roughness: 0.4 });
    const tip = new THREE.Mesh(new THREE.ConeGeometry(0.18, 0.6, 16), new THREE.MeshStandardMaterial({ color: 0xd0d4d8, metalness: 0.8 }));
    tip.rotation.x = Math.PI;
    tip.position.y = 0.3;
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.3, 0.3, 2, 16), mat);
    body.position.y = 1.6;
    const tag = flatLabel(p.id, 1.2, { px: 48 });
    tag.rotation.x = 0;
    tag.position.set(0, 3, 0);
    g.add(tip, body, tag);
    const saved = opts.probePositions[p.id];
    const pos = saved && Number.isFinite(saved.x) && Number.isFinite(saved.z) ? [saved.x, 0, saved.z] : p.home;
    g.position.set(...pos);
    g.userData.net = saved && saved.net ? saved.net : null;
    scene.add(g);
    probes.set(p.id, g);
    pickables.push(g);
  }

  // ---- camera and controls ----------------------------------------------------
  const { clamp } = THREE.MathUtils;
  const orbit = { target: new THREE.Vector3(0, 0, 0), radius: 34, theta: 0, phi: 0.85 };
  const placeCamera = () => {
    const { target, radius, theta, phi } = orbit;
    camera.position.setFromSphericalCoords(radius, phi, theta).add(target);
    camera.lookAt(target);
  };
  const raycaster = new THREE.Raycaster();
  const ndc = new THREE.Vector2();
  const plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const ray = (ev) => {
    const r = canvas.getBoundingClientRect();
    ndc.set(((ev.clientX - r.left) / r.width) * 2 - 1, -((ev.clientY - r.top) / r.height) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    return raycaster;
  };
  const owner = (obj) => {
    for (let o = obj; o; o = o.parent) {
      if (o.userData.probe) return { probe: o.userData.probe };
      if (o.userData.net) return { net: o.userData.net };
      if (o.userData.id) return { id: o.userData.id };
    }
    return null;
  };
  const pick = (ev) => {
    for (const hit of ray(ev).intersectObjects(pickables, true)) {
      const o = owner(hit.object);
      if (o) return o;
    }
    return null;
  };

  const render = () => {
    res.frameRequest = 0;
    if (res.disposed) return;
    placeCamera();
    renderer.render(scene, camera);
    if (opts.onRendered) opts.onRendered(hotspots());
  };
  const requestRender = () => {
    if (!res.frameRequest && !res.disposed) res.frameRequest = requestAnimationFrame(render);
  };
  const resize = () => {
    const w = Math.max(100, host.clientWidth);
    const h = Math.max(100, host.clientHeight);
    renderer.setSize(w, h, true);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    requestRender();
  };
  res.resizer = new ResizeObserver(resize);
  res.resizer.observe(host);

  function hotspots() {
    const r = canvas.getBoundingClientRect();
    const project = (v) => {
      const p = v.project(camera);
      return { x: ((p.x + 1) / 2) * r.width, y: ((1 - p.y) / 2) * r.height };
    };
    const out = [];
    for (const c of COMPONENTS) {
      const g = groups.get(c.id);
      out.push(Object.assign({ kind: 'component', id: c.id }, project(g.position.clone().add(new THREE.Vector3(0, c.size[1], 0)))));
    }
    out.push(Object.assign({ kind: 'component', id: 'vga', name: 'screen' }, project(monitor.position.clone().add(new THREE.Vector3(0, 0, 0.3)))));
    for (const [id, m] of netMeshes) out.push(Object.assign({ kind: 'net', id }, project(m.position.clone().setY(0.25))));
    for (const [id, g] of probes) {
      out.push(Object.assign({ kind: 'probe', id, net: g.userData.net }, project(g.position.clone().setY(1.6))));
    }
    return out;
  }

  // ---- pointer handling: orbit, pan, zoom, pick, probe drag ----------------------------
  const listeners = res.listeners;
  const on = (target, type, fn, o) => {
    target.addEventListener(type, fn, o);
    listeners.push(() => target.removeEventListener(type, fn, o));
  };
  let drag = null;
  on(canvas, 'contextmenu', (ev) => ev.preventDefault());
  on(canvas, 'pointerdown', (ev) => {
    const hit = pick(ev);
    drag = { x: ev.clientX, y: ev.clientY, moved: false, hit,
      mode: hit && hit.probe ? 'probe' : ev.button === 2 || ev.shiftKey ? 'pan' : 'orbit',
      theta: orbit.theta, phi: orbit.phi, target: orbit.target.clone() };
    canvas.setPointerCapture(ev.pointerId);
  });
  on(canvas, 'pointermove', (ev) => {
    if (!drag) {
      canvas.style.cursor = pick(ev) ? 'pointer' : 'grab';
      return;
    }
    const dx = ev.clientX - drag.x;
    const dy = ev.clientY - drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
    if (!drag.moved) return;
    if (drag.mode === 'probe') {
      const p = new THREE.Vector3();
      if (ray(ev).ray.intersectPlane(plane, p)) {
        const g = probes.get(drag.hit.probe);
        g.position.set(clamp(p.x, -BOARD.width / 2, BOARD.width / 2), 0, clamp(p.z, -BOARD.depth / 2, BOARD.depth / 2));
      }
    } else if (drag.mode === 'orbit') {
      orbit.theta = drag.theta - dx * 0.006;
      orbit.phi = clamp(drag.phi - dy * 0.006, 0.12, 1.45);
    } else {
      const k = orbit.radius / 600;
      const right = new THREE.Vector3(Math.cos(orbit.theta), 0, -Math.sin(orbit.theta));
      const fwd = new THREE.Vector3(Math.sin(orbit.theta), 0, Math.cos(orbit.theta));
      orbit.target.copy(drag.target).addScaledVector(right, -dx * k).addScaledVector(fwd, -dy * k);
    }
    requestRender();
  });
  on(canvas, 'pointerup', () => {
    const d = drag;
    drag = null;
    if (!d) return;
    if (d.mode === 'probe' && d.moved) {
      const g = probes.get(d.hit.probe);
      let best = null;
      for (const n of NETS) {
        const dist = Math.hypot(n.pos[0] - g.position.x, n.pos[2] - g.position.z);
        if (dist <= SNAP && (!best || dist < best.dist)) best = { n, dist };
      }
      if (best) g.position.set(best.n.pos[0], 0, best.n.pos[2]);
      g.userData.net = best ? best.n.id : null;
      requestRender();
      opts.onProbe(d.hit.probe, best ? best.n : null, { x: g.position.x, z: g.position.z });
      return;
    }
    if (d.moved || !d.hit) return;
    if (d.hit.id) opts.onSelect(d.hit.id);
    else if (d.hit.net) opts.onSelect(NET_OWNER[d.hit.net]);
  });
  on(canvas, 'wheel', (ev) => {
    ev.preventDefault();
    orbit.radius = clamp(orbit.radius * (ev.deltaY > 0 ? 1.1 : 1 / 1.1), 10, 70);
    requestRender();
  }, { passive: false });

  resize();
  placeCamera();
  renderer.render(scene, camera); // the first frame now, so that its time can be measured
  renderer.getContext().finish();

  return { hotspots, dispose: () => release(res) };
}

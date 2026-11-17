<!-- SPDX-License-Identifier: MIT -->
# three.js, vendored

The 3D virtual teaching board (`web/board/`, OBS-07) uses three.js. The site has no build step and
loads nothing from a CDN at run time, so the library's ES module build is kept here unchanged.

| | |
|---|---|
| Project | three.js, <https://threejs.org>, <https://github.com/mrdoob/three.js> |
| Release | **0.186.1**, published to npm on 2026-09-24 (`npm view three@0.186.1 time`) |
| License | MIT, [`LICENSE`](LICENSE) (copied from the package, unchanged) |
| Source | the npm package `three@0.186.1`: <https://registry.npmjs.org/three/-/three-0.186.1.tgz> (SHA-256 `8cd068708ea44f2c73c944b1cead2ba2f0d5c15c8fc194e5700f4e4f4a033fe7`, npm integrity `sha512-blFeqb49wRCSGUGj7gtpfnSGHy2lwDk94RhUmS1c/hTby70kvChbWpkJ4Pm1390LqzzvTmzgXKHPEafJwCb8jA==`) |
| Fetched | 2026-11-15, from `https://cdn.jsdelivr.net/npm/three@0.186.1/<path>`; each file is byte for byte the one in the tarball above |

| File | Path in the package | Bytes | SHA-256 |
|------|---------------------|------:|---------|
| `three.module.js` | `build/three.module.js` | 662,772 | `9052042d676cb0fdc1ddfefe193053f34b7ac0513a616fdac4535d49987812ea` |
| `three.core.js` | `build/three.core.js` | 1,458,113 | `9edde002b066a9a05676a6127f67735b62baf399bdea529f2f7e31657da769e6` |
| `LICENSE` | `LICENSE` | 1,081 | `8b378ebe60e2fe500158cb0ac71cb5e8b7d92953c2abcc63a0eb90499653b5bc` |

Since release 0.170 the ES module build is split in two: `three.module.js` imports
`./three.core.js`, so both files are needed. The files keep their upstream headers
(`SPDX-License-Identifier: MIT`, "Copyright 2010-2026 Three.js Authors"); nothing is added to them.
No add-on from `examples/jsm` is vendored: the board's orbit, pan and zoom controls are its own
code (`web/board/scene.js`).

To check the files:

```
cd web/vendor/three && sha256sum three.module.js three.core.js LICENSE
```

To move to another release, fetch the same three paths of that release, replace the files, and
update this page (release, date, hashes). Pick a release that is at least two weeks old.

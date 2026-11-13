# Synaxis: Connect beyond the 4th Wall

> The internal modId stays `cc_virtual_coms` for save and script compatibility;
> the old display name "CC Synaxis Virtual Coms" is kept only as history.

Virtual serial ports that bridge Minecraft circuits, CC:Tweaked computers and
out-of-game Python programs over a local TCP socket.

- **Author**: Axolotl114
- **Version**: 0.0.1
- **MC / Loader**: 1.21.1 / NeoForge 21.1.236

## Requirements

| Mod | Purpose |
| --- | --- |
| Synaxis | Cimulink circuits |
| CC:Tweaked | Computers and peripherals |
| Create / Sable / LDLib2 | Synaxis dependency chain |

## Block and item

### Virtual Serial Port Board `virtual_coms_board`

The only block this mod adds (which is also its item). Found in its own
"Synaxis: Connect beyond the 4th Wall" creative tab.

- **Placement**: mounts on the face you click, like other Synaxis circuit
  boards (`facing` has all six directions).
- **Configuration screen**: right-click with an **empty hand** to open it.
  Right-clicking while holding any item does not open it — the interaction is
  passed to the item instead, so the Synaxis tool gun still works normally for
  wiring the board into a circuit.
- **What the screen does**: set the serial port id (`COM1` etc.); add and
  remove ports; cycle a port's type through `real` / `boolean` / `vec3` /
  `quad`; cycle its direction through `in` / `out`; see each port's current
  value and who last wrote it (`python` / `cc` / `synaxis`); toggle
  "keep last value".
- **Wiring**: registers as the Cimulink component
  `cc_virtual_coms:serial_board`. `in` ports become circuit inputs, `out`
  ports become circuit outputs.

### Port direction

| Direction | Meaning |
| --- | --- |
| `out` | The circuit **reads** this port — write to it from Python or Lua to drive the circuit |
| `in` | The circuit **drives** this port — read it from Python or Lua to observe the circuit |

Read and write are not restricted by direction. If a port is currently being
driven by the circuit (the value column shows `[synaxis]`), a value you write
is overwritten on the next game tick.

### Camera Transmitter `camera_transmitter`

Streams the block's view to programs outside the game (Zoom, Discord, OBS, or any
browser that can open a camera).

- **Placement and screen**: six-way `facing`, uses the vanilla observer model;
  right-click with an empty hand to configure (items keep their own interaction).
- **Video output**: resolution (4:3 / 16:9 presets plus custom width/height),
  frame rate 15-60, colour / monochrome, night vision (monochrome + night vision
  is a thermal-style false-colour look).
- **Trigger**: circuit/redstone switch, hold on / hold off, redstone mode
  (hold / toggle), threshold 0-15, two Create wireless redstone frequency slots.
  The full priority table is in the GUI tooltips and in the
  `CameraConfig` / `CameraTransmitterBlockEntity` JavaDoc.
- **Camera**: yaw / pitch / roll can each be "world stable" or "follow
  sub-level"; the local position offset is configurable. On a Sable sub-level the
  camera follows the ship's interpolated `renderPose`.
- **Rendering**: shader rendering "on / off / follow player". Without an active
  shader pack capture uses a private off-screen target and never touches the
  player's view; with a shader pack it captures the main render target (at most
  10 times per second) and re-renders the player view in the same frame so the
  screen does not flicker.
- **Output device**: on Windows every camera is bound to a DirectShow video
  input device `Synaxis Camera 1..4` (four slots). Run `/ccvc_cameras install`
  in game once; it asks for one UAC confirmation (no system reboot; restart the
  external application to see the device). If the driver is not registered the
  MJPEG fallback is used and the state is reported as `driver_unavailable`
  everywhere (GUI, log, `/api/cameras`):
  - stream: `http://127.0.0.1:25586/camera/<position-hex>/stream.mjpeg`
  - snapshot: `http://127.0.0.1:25586/camera/<position-hex>/snapshot.jpg`
  - status: `http://127.0.0.1:25586/api/cameras`
  The port can be overridden with `-Dcc_virtual_coms.camera.httpPort=<port>`
  and is only bound to `127.0.0.1`.
- **Offline behaviour**: when the stream is off, the chunk is unloaded or the
  trigger is inactive, the device stays present and keeps emitting a black
  "offline" frame; breaking the block unregisters its device instance.
- **Synaxis terminals**: `enable` (boolean in), `streaming` (boolean out),
  `euler_angle_in` / `euler_angle_out` (vec3 `yaw, pitch, roll`; Synaxis requires
  unique port names, so the bidirectional terminal is a matched input/output
  pair).
- **Commands**: `/ccvc_cameras status|install|uninstall|cleanup`,
  `/ccvc_camera_debug status|pose|dump`.
- **Limitations**: the DirectShow device is x64 / RGB24 only (32-bit external
  applications cannot see it); shader capture renders the player view a second
  time (at most 10 times per second), so lower the resolution/frame rate on slow
  machines.

## Python API

File `python/cc_virtual_coms.py`. Standard library only, Python 3.8+.

```python
from cc_virtual_coms import Coms

coms = Coms()                              # connects to 127.0.0.1:25585

coms.send("drive", 0.25)                   # pack + send
coms.send("gear", True)                    # type inferred
coms.send("target", [1.0, 64.0, 2.0])      # vec3

port, ptype, value = coms.recv()           # blocking receive, unpacked
print(coms.get("sense"))                   # latest cached value

for port, ptype, value in coms:            # stream incoming values
    print(port, ptype, value)
```

Main `Coms` methods:

| Group | Methods |
| --- | --- |
| Pack | `pack(port, value, port_type=None)` / `pack_payload(...)` |
| Send | `send(frame)` / `send(port, value)` / `write(...)` / `heartbeat()` |
| Receive | `recv(timeout=None)` / `poll(timeout=0)` / `recv_all(...)` / `for ... in coms` |
| Read | `get(port, default=None)` / `coms[port]` / `read(port, default=None, wait=None)` |
| Status | `connected` / `ports` / `list_ports()` / `out_ports()` / `in_ports()` / `port_com(p)` / `port_direction(p)` / `last_error` / `close()` |

Notes:

- `Coms()` connects automatically and starts a background receiver thread, so
  `send()` still works while `recv()` blocks. Use `Coms(background=False)` plus
  `poll()` if you don't want a thread.
- Port types are decided by the game — prefer the reported type. A mismatch is
  reported clearly and can be overridden with `port_type=`.
- Every push carries its serial port id, so `send("spark", 1.0)` routes to the
  board that owns `spark`; multiple boards need no manual targeting.
- **Create one connection and reuse it.** Do not call `Coms()` in a loop — that
  leaks TCP connections until you hit the mod's limit (64 by default).

Lower-level helpers, if you want to drive a socket yourself: `pack_message`,
`unpack_message`, `read_message`, `pack_port_value`, `unpack_port_value`,
`infer_port_type`, `pack_binary_port_value`, `unpack_binary_port_value`
(compact binary encoding), plus the constants `MAGIC`, `VERSION`,
`HEADER_SIZE`, `MSG_*`, `PORT_TYPES`. The underlying `VirtualComsClient` class
exposes `write_port`, `pump`, `listen`, `on_data` and friends.

### Python camera API

```python
from cc_virtual_coms import CameraStream, Coms

coms = Coms()                                  # connects to 127.0.0.1:25585
for camera in coms.cameras():                  # pushed by the mod over TCP 0x10
    print(camera["name"], camera["status"], camera["stream_url"])
print(coms.camera_status("Synaxis Camera 1"))  # streaming / offline / driver_unavailable

stream = CameraStream.from_camera(coms.cameras()[0])
stream.open()
frame = stream.read_frame()                    # raw JPEG bytes, None on timeout
if frame:
    stream.save("camera_frame.jpg")            # or save_frames("dir", count=10)
stream.close()
```

`CameraStream` also accepts a direct `stream_url`, or `host`/`port` plus a
`pos`/`name`. `show()` opens a live window when Pillow + Tk are installed and
otherwise falls back to handing a temporary JPEG to the system viewer; the
zero-dependency path is `save()`.

## Lua API

On a CC:Tweaked computer:

```lua
local coms = peripheral.find("cc_virtual_coms")   -- alias: "virtual_coms"
```

```lua
-- read and write
local value, ptype = coms.readPort("altitude")
coms.writePort("throttle", 42.5)
coms.writePort("gear", true)
coms.writePort("target", {1.0, 2.0, 3.0})         -- vec3, also {x=,y=,z=}
coms.writePort("attitude", {0.0, 0.0, 0.0, 1.0})  -- quad

-- port list and connection state
for _, p in ipairs(coms.listPorts()) do
  print(p.name, p.type, p.direction, p.value)
end
print(coms.isConnected())
```

| Group | Methods |
| --- | --- |
| Core | `readPort(name)` / `writePort(name, value)` / `listPorts()` / `isConnected()` |
| Configure | `getComPort()` / `setComPort(id)` / `addPort([name], type, direction)` / `removePort(name)` / `setPortType(name, type)` / `setPortDirection(name, dir)` / `clearPorts()` |
| Diagnose | `getPortInfo(name)` / `getStatus()` / `getConnectionCount()` / `getServerAddress()` / `getLastError()` / `getDirectionLegend()` |

`type` is `"real"` / `"boolean"` / `"vec3"` / `"quad"`; `direction` is `"in"` /
`"out"`.

Events: when an `in` port receives a new value, attached computers get an
event.

```lua
local event, port, value = os.pullEvent("cc_virtual_coms")
```

## Building from source

Requires JDK 21. Dependencies are supplied as local jars from `libs/`, so the
build needs no mod repository access, and they are not bundled into the output.

```bash
./gradlew build          # output in build/libs/
./gradlew runClient      # client with the mod
./gradlew runServer      # server
```

## Credits

This mod's display name is **Synaxis: Connect beyond the 4th Wall**; the word
"Synaxis" is a tribute to the upstream **Synaxis** mod.

- **GUI textures** are reused from **Synaxis** by **Rew1nd** (modId `synaxis`,
  licensed "All Rights Reserved") **with the explicit permission of the author**.
  Copyright remains with the original author; these textures are not covered by
  this mod's MIT license.
- **The block model and block textures** (`base` / `pins` / `chip`) are
  derivative works redrawn and recoloured from Synaxis reference material. They
  differ substantially from the originals in `synaxis-1.4.3.jar` (per-pixel
  comparison: `pins` 256/256 = **100%** (fully redrawn), `base` 251/256 =
  **98.05%**, `chip` 1055/4096 = **25.76%**); they are not the original files.
- **New blocks** such as the **camera transmitter** (`camera_transmitter`) do
  **not** reuse Synaxis block models or Synaxis block textures: their block
  model references the vanilla `minecraft:block/observer` by resource location
  only, and no extra block textures are shipped.
- Built on **Synaxis**, **CC:Tweaked** and **Create**.
- Dependency chain: **Sable** / **LDLib2** / **Create Aeronautics**.

> The full legal statement for third-party art assets is in [LICENSE](LICENSE).

## License

MIT — see [LICENSE](LICENSE).

Note that third-party art assets (the Synaxis GUI textures and the block
textures derived from Synaxis) are **not** covered by the MIT license; see the
third-party art assets section of [LICENSE](LICENSE).

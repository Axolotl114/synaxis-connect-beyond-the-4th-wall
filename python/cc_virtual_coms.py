"""
cc_virtual_coms
===============

Python client library for the **CC Synaxis Virtual Coms** Minecraft mod.

The mod opens a local TCP listener (``127.0.0.1:25585`` by default). This module speaks its
framing protocol so an ordinary Python program can exchange values with virtual serial port
boards placed in the world, and through them with Synaxis Cimulink circuits and CC:Tweaked
computers.

Frame layout (all integers big-endian)::

    +--------+-----------+-----------+------------+-----------------+
    |  magic |  version  |  msg type |  payload   |    payload      |
    | 4 byte |  2 byte   |  2 byte   |  length    |   (JSON UTF-8)  |
    | "CCVC" |  uint16   |  uint16   |  uint32    |     N byte      |
    +--------+-----------+-----------+------------+-----------------+

Message types
-------------

======================  ======  ===================================================
Name                    Value   Direction / meaning
======================  ======  ===================================================
``MSG_DATA_PUSH``       0x01    game -> Python; a port value changed
``MSG_DATA_WRITE``      0x02    Python -> game; write a port value
``MSG_HEARTBEAT``       0x03    either direction; liveness probe
``MSG_ERROR``           0x04    either direction; carries ``{"error": "..."}``
``MSG_CAMERA_LIST``     0x10    game -> Python; camera metadata + stream status snapshot
======================  ======  ===================================================

Port value types
----------------

===========  ==================  ===========================================
Mod type     Wire type           Python value
===========  ==================  ===========================================
``real``     float64             ``float``
``boolean``  uint8               ``bool``
``vec3``     float64[3]          ``[x, y, z]``
``quad``     float64[4]          ``[x, y, z, w]``
===========  ==================  ===========================================

Quick start
-----------

四行就够：**连接、打包、发送、接收并解包**，再配一个读取方法。

::

    from cc_virtual_coms import Coms

    coms = Coms()                             # 连接 127.0.0.1:25585 / COM1

    frame = coms.pack("drive", 0.25)          # 一行打包
    coms.send(frame)                          # 一行发送

    port, ptype, value = coms.recv()          # 一行接收并解包
    print(coms.get("sense"))                  # 配套读取方法

``send`` 也可以一行搞定打包与发送::

    coms.send("drive", 0.25)
    coms.send("gear", True)
    coms.send("target", [1.0, 64.0, 2.0])

要看游戏推回来的值，直接迭代即可::

    for port, ptype, value in coms:
        print(port, ptype, value)

Camera streaming (图传摄像头)
-----------------------------

Camera metadata travels through the *same* 25585 protocol as one extra game -> Python push,
``MSG_CAMERA_LIST`` (0x10). Video never travels over TCP: the mod runs a separate loopback MJPEG
server (default ``http://127.0.0.1:25586``; override with
``-Dcc_virtual_coms.camera.httpPort=<port>`` on the game side) exposing
``/stream.mjpeg``, ``/camera/<packed-pos-hex>/stream.mjpeg``, ``/camera/<packed-pos-hex>/snapshot.jpg``
and ``/api/cameras``. The camera list is pushed once when a client connects and again whenever it
changes, so accessors below never block::

    from cc_virtual_coms import CameraStream, Coms

    coms = Coms()                                  # 连接游戏

    for camera in coms.cameras():                  # 非阻塞：读后台泵缓存的快照
        print(camera["name"], camera["status"], camera["resolution"], camera["stream_url"])

    print(coms.camera_status("cam0"))              # "streaming" / "offline" / "driver_unavailable"

    camera = CameraStream.from_camera(coms.cameras()[0])   # 或用 stream_url/pos/host+port 直接构造
    camera.open()
    frame = camera.read_frame(timeout=2.0)         # 完整 JPEG bytes；超时返回 None
    camera.save("frame.jpg")                       # 零依赖落盘（原始 JPEG）
    camera.show()                                  # 可选预览：PIL+Tk 优先，否则系统查看器，失败返回 False
    camera.close()

``cameras()`` returns ``[]`` when the game is an older build without the camera feature or when no
snapshot has arrived yet; ``camera_status()`` then returns its ``default`` (``None`` unless given).
``CameraStream`` needs only the standard library; Pillow/tkinter are optional and only make
``show()`` smoother.

底层 API（``pack_message`` / ``unpack_message`` / ``VirtualComsClient``）依然可用，
``Coms`` 只是它上面的一层薄封装。
"""

from __future__ import annotations

import collections
import io
import json
import os
import queue
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import weakref
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple
from urllib.parse import urlsplit

__all__ = [
    "MAGIC",
    "VERSION",
    "HEADER_SIZE",
    "MSG_DATA_PUSH",
    "MSG_DATA_WRITE",
    "MSG_HEARTBEAT",
    "MSG_ERROR",
    "MSG_CAMERA_LIST",
    "MESSAGE_TYPE_NAMES",
    "PORT_TYPES",
    "DIRECTION_IN",
    "DIRECTION_OUT",
    "DEFAULT_CAMERA_HOST",
    "DEFAULT_CAMERA_PORT",
    "CAMERA_STREAMING",
    "CAMERA_OFFLINE",
    "CAMERA_DRIVER_UNAVAILABLE",
    "CAMERA_STATUSES",
    "ProtocolError",
    "NotConnectedError",
    "CameraError",
    "CameraStreamError",
    "MjpegProtocolError",
    "pack_message",
    "unpack_message",
    "pack_port_value",
    "unpack_port_value",
    "encode_port_value_binary",
    "decode_port_value_binary",
    "pack_binary_port_value",
    "unpack_binary_port_value",
    "infer_port_type",
    "coerce_port_value",
    "parse_multipart_boundary",
    "parse_part_headers",
    "parse_http_head",
    "looks_like_jpeg",
    "MjpegStreamParser",
    "camera_position_key",
    "camera_position_hex",
    "camera_stream_path",
    "CameraStream",
    "VirtualComsClient",
    "Coms",
    "VirtualComs",
    "connect",
]

# --------------------------------------------------------------------------------------
# Protocol constants
# --------------------------------------------------------------------------------------

MAGIC = b"CCVC"
VERSION = 1

#: ``>4s H H I`` - magic, version, message type, payload length.
HEADER = struct.Struct(">4sHHI")
HEADER_SIZE = HEADER.size  # 12

MSG_DATA_PUSH = 0x01
MSG_DATA_WRITE = 0x02
MSG_HEARTBEAT = 0x03
MSG_ERROR = 0x04
#: Game -> external only: camera metadata and stream status snapshot. Pushed when a client
#: connects and again whenever the camera set changes. Older clients must ignore it; this client
#: never sends it (see :class:`CameraStream` for the separate HTTP video channel).
MSG_CAMERA_LIST = 0x10

MESSAGE_TYPE_NAMES = {
    MSG_DATA_PUSH: "DATA_PUSH",
    MSG_DATA_WRITE: "DATA_WRITE",
    MSG_HEARTBEAT: "HEARTBEAT",
    MSG_ERROR: "ERROR",
    MSG_CAMERA_LIST: "CAMERA_LIST",
}

TYPE_REAL = "real"
TYPE_BOOLEAN = "boolean"
TYPE_VEC3 = "vec3"
TYPE_QUAD = "quad"

PORT_TYPES = (TYPE_REAL, TYPE_BOOLEAN, TYPE_VEC3, TYPE_QUAD)

#: Terminal directions. ``out`` means the Cimulink circuit *reads* the terminal, so an out-of-game
#: program may drive it without the circuit ever overwriting the value. ``in`` means the circuit
#: *drives* the terminal and an out-of-game program should read it instead.
DIRECTION_IN = "in"
DIRECTION_OUT = "out"

#: Scalar layout of each port type, matching the documented wire encoding.
PORT_TYPE_STRUCTS = {
    TYPE_REAL: struct.Struct(">d"),
    TYPE_BOOLEAN: struct.Struct(">B"),
    TYPE_VEC3: struct.Struct(">3d"),
    TYPE_QUAD: struct.Struct(">4d"),
}

#: Number of scalar components per type.
PORT_TYPE_ARITY = {TYPE_REAL: 1, TYPE_BOOLEAN: 1, TYPE_VEC3: 3, TYPE_QUAD: 4}

#: ``>H`` length prefix used by the self describing binary port record.
_LENGTH = struct.Struct(">H")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25585

#: Guard against a desynchronised or hostile peer declaring an enormous payload.
MAX_PAYLOAD_SIZE = 8 * 1024 * 1024

# --------------------------------------------------------------------------------------
# Camera constants
# --------------------------------------------------------------------------------------

#: The camera fallback stream is a separate loopback HTTP server. Mirrors the Java side default
#: in ``CameraStreamUrls``; if the player overrides it with
#: ``-Dcc_virtual_coms.camera.httpPort=<port>``, pass the same value to :class:`CameraStream`.
DEFAULT_CAMERA_HOST = "127.0.0.1"
DEFAULT_CAMERA_PORT = 25586

#: Status strings reported by the mod for each camera (serialized ``CameraStreamStatus``).
CAMERA_STREAMING = "streaming"
CAMERA_OFFLINE = "offline"
CAMERA_DRIVER_UNAVAILABLE = "driver_unavailable"
CAMERA_STATUSES = (CAMERA_STREAMING, CAMERA_OFFLINE, CAMERA_DRIVER_UNAVAILABLE)

#: Size guards for the MJPEG fallback stream (same "bounded parser" philosophy as the TCP frame).
MAX_MJPEG_FRAME_SIZE = 8 * 1024 * 1024
MAX_MJPEG_HEADER_SIZE = 64 * 1024
MAX_MJPEG_BUFFER_SIZE = 16 * 1024 * 1024
MAX_HTTP_HEAD_SIZE = 64 * 1024
MJPEG_READ_CHUNK = 65536
DEFAULT_STREAM_TIMEOUT = 5.0
_MJPEG_BOUNDARY_MAX_LEN = 200
_MJPEG_BOUNDARY_TAIL = re.compile(rb"[ \t]*\r?\n")


class ProtocolError(Exception):
    """Raised when a peer sends something that is not a valid frame."""


class NotConnectedError(Exception):
    """Raised when an operation needs a live connection but none exists."""


class CameraError(ValueError):
    """Raised when the camera metadata pushed by the game is structurally malformed."""


class CameraStreamError(Exception):
    """Raised when a camera MJPEG stream cannot be opened or became unusable."""


class MjpegProtocolError(CameraStreamError):
    """Raised when MJPEG multipart framing is malformed or a size guard is exceeded."""


# --------------------------------------------------------------------------------------
# Frame level: pack_message / unpack_message
# --------------------------------------------------------------------------------------


def pack_message(msg_type: int, payload: Optional[dict] = None) -> bytes:
    """Pack a complete protocol frame.

    :param msg_type: one of the ``MSG_*`` constants.
    :param payload: JSON-serialisable mapping, or ``None`` for an empty payload.
    :returns: the framed message as ``bytes``.
    :raises ValueError: if ``msg_type`` is not a 16 bit unsigned integer.
    """
    if not 0 <= int(msg_type) <= 0xFFFF:
        raise ValueError("message type must fit in 16 bits, got %r" % (msg_type,))

    if payload is None:
        body = b""
    else:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")

    if len(body) > MAX_PAYLOAD_SIZE:
        raise ValueError("payload of %d bytes exceeds the protocol limit" % len(body))

    return HEADER.pack(MAGIC, VERSION, int(msg_type), len(body)) + body


def unpack_message(data: bytes) -> Tuple[int, dict]:
    """Unpack a complete protocol frame.

    :param data: the full frame (header plus payload).
    :returns: ``(message_type, payload_dict)``.
    :raises ProtocolError: on a bad magic number, an unsupported version, a truncated frame or
        malformed JSON.
    """
    if data is None or len(data) < HEADER_SIZE:
        raise ProtocolError("frame is shorter than the %d byte header" % HEADER_SIZE)

    magic, version, msg_type, length = HEADER.unpack_from(data, 0)

    if magic != MAGIC:
        raise ProtocolError("bad frame magic %r, expected %r" % (magic, MAGIC))
    if version != VERSION:
        raise ProtocolError("unsupported protocol version %d, this library speaks %d" % (version, VERSION))
    if length > MAX_PAYLOAD_SIZE:
        raise ProtocolError("declared payload of %d bytes exceeds the protocol limit" % length)

    body = data[HEADER_SIZE:HEADER_SIZE + length]
    if len(body) != length:
        raise ProtocolError("frame declares %d payload bytes but only %d are present" % (length, len(body)))

    if not body:
        return msg_type, {}

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("payload is not valid UTF-8 JSON: %s" % exc)

    if not isinstance(payload, dict):
        raise ProtocolError("payload must be a JSON object, got %s" % type(payload).__name__)

    return msg_type, payload


def read_message(sock: socket.socket) -> Tuple[int, dict]:
    """Read exactly one frame from a socket, blocking until it arrives.

    :raises EOFError: when the peer closed the connection at a frame boundary.
    :raises ProtocolError: when the frame is malformed.
    """
    header = _recv_exactly(sock, HEADER_SIZE)
    magic, version, msg_type, length = HEADER.unpack(header)

    if magic != MAGIC:
        raise ProtocolError("bad frame magic %r, expected %r" % (magic, MAGIC))
    if version != VERSION:
        raise ProtocolError("unsupported protocol version %d, this library speaks %d" % (version, VERSION))
    if length > MAX_PAYLOAD_SIZE:
        raise ProtocolError("declared payload of %d bytes exceeds the protocol limit" % length)

    body = _recv_exactly(sock, length) if length else b""
    if not body:
        return msg_type, {}

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("payload is not valid UTF-8 JSON: %s" % exc)

    return msg_type, payload if isinstance(payload, dict) else {}


def _recv_exactly(sock: socket.socket, count: int) -> bytes:
    """Read exactly ``count`` bytes or raise :class:`EOFError`."""
    if count == 0:
        return b""

    chunks = []
    remaining = count
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("connection closed after %d of %d bytes" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


# --------------------------------------------------------------------------------------
# Port value level
# --------------------------------------------------------------------------------------


def infer_port_type(value: Any) -> str:
    """Guess the port type that best fits a Python value.

    ``bool`` -> ``boolean``, ``int``/``float`` -> ``real``, a 3 element sequence -> ``vec3``,
    a 4 element sequence -> ``quad``.
    """
    if isinstance(value, bool):
        return TYPE_BOOLEAN
    if isinstance(value, (int, float)):
        return TYPE_REAL
    if isinstance(value, (list, tuple)):
        if len(value) == 3:
            return TYPE_VEC3
        if len(value) == 4:
            return TYPE_QUAD
    raise ValueError(
        "cannot infer a port type from %r; pass port_type explicitly "
        "(one of %s)" % (value, ", ".join(PORT_TYPES))
    )


def coerce_port_value(port_type: str, value: Any) -> Any:
    """Validate and normalise ``value`` for ``port_type``.

    Returns the JSON facing representation: a float, a bool, or a list of 3/4 floats.
    """
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r; expected one of %s" % (port_type, ", ".join(PORT_TYPES)))

    if port_type == TYPE_REAL:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("port type 'real' needs a number, got %r" % (value,))
        return float(value)

    if port_type == TYPE_BOOLEAN:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return bool(value)
        raise ValueError("port type 'boolean' needs a bool, got %r" % (value,))

    arity = PORT_TYPE_ARITY[port_type]
    if not isinstance(value, (list, tuple)):
        raise ValueError("port type %r needs a sequence of %d numbers, got %r" % (port_type, arity, value))
    if len(value) != arity:
        raise ValueError("port type %r needs exactly %d components, got %d" % (port_type, arity, len(value)))

    result = []
    for index, component in enumerate(value):
        if isinstance(component, bool) or not isinstance(component, (int, float)):
            raise ValueError("component %d of port type %r must be a number, got %r"
                             % (index, port_type, component))
        result.append(float(component))
    return result


def pack_port_value(port_name: str, port_type: str, value: Any, com: Optional[str] = None) -> bytes:
    """Pack a port value into the JSON payload used by ``DATA_PUSH`` / ``DATA_WRITE``.

    :param port_name: the terminal name configured on the board.
    :param port_type: one of ``real``, ``boolean``, ``vec3``, ``quad``.
    :param value: the value; validated against ``port_type``.
    :param com: optional serial port id (``"COM1"``) so the mod can route to a specific board.
    :returns: UTF-8 encoded JSON bytes, ready to be used as a frame payload.
    """
    if not port_name:
        raise ValueError("port_name is required")

    payload = {
        "port": str(port_name),
        "type": port_type,
        "value": coerce_port_value(port_type, value),
    }
    if com:
        payload["com"] = str(com)
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def unpack_port_value(payload: dict) -> Tuple[str, str, Any]:
    """Unpack a port value payload.

    :param payload: a decoded JSON payload mapping.
    :returns: ``(port_name, port_type, value)`` where ``value`` is a ``float``, ``bool`` or a
        list of floats.
    :raises ProtocolError: when required fields are missing or unknown.
    """
    if not isinstance(payload, dict):
        raise ProtocolError("port payload must be a mapping, got %s" % type(payload).__name__)

    port_name = payload.get("port")
    if not port_name:
        raise ProtocolError("port payload is missing the 'port' field")

    port_type = payload.get("type")
    if port_type not in PORT_TYPE_STRUCTS:
        raise ProtocolError("port payload has unknown type %r" % (port_type,))

    raw = payload.get("value")
    try:
        value = coerce_port_value(port_type, raw)
    except ValueError as exc:
        raise ProtocolError("invalid value for port %r: %s" % (port_name, exc))

    return str(port_name), port_type, value


# --------------------------------------------------------------------------------------
# Optional compact binary port records
# --------------------------------------------------------------------------------------


def encode_port_value_binary(port_type: str, value: Any) -> bytes:
    """Encode a value using the documented scalar layout.

    ``real`` -> float64, ``boolean`` -> uint8, ``vec3`` -> float64[3], ``quad`` -> float64[4].
    """
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    normalised = coerce_port_value(port_type, value)
    if port_type == TYPE_BOOLEAN:
        return PORT_TYPE_STRUCTS[port_type].pack(1 if normalised else 0)
    if port_type in (TYPE_VEC3, TYPE_QUAD):
        return PORT_TYPE_STRUCTS[port_type].pack(*normalised)
    return PORT_TYPE_STRUCTS[port_type].pack(normalised)


def decode_port_value_binary(port_type: str, data: bytes) -> Any:
    """Decode a value produced by :func:`encode_port_value_binary`."""
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    expected = PORT_TYPE_STRUCTS[port_type].size
    if len(data) != expected:
        raise ProtocolError("port type %r needs %d bytes, got %d" % (port_type, expected, len(data)))

    values = PORT_TYPE_STRUCTS[port_type].unpack(data)
    if port_type == TYPE_REAL:
        return values[0]
    if port_type == TYPE_BOOLEAN:
        return bool(values[0])
    return list(values)


def pack_binary_port_value(port_name: str, port_type: str, value: Any, com: Optional[str] = None) -> bytes:
    """Pack a port value into the compact, self describing binary record.

    Layout::

        [uint16 com_len][com utf8][uint16 name_len][name utf8][uint16 type_len][type utf8][value]

    This is an alternative to the JSON payload for latency sensitive loops. The mod's live socket
    protocol uses the JSON payload; this record is provided so both sides can exchange values in
    the documented scalar encoding when a caller wants it.
    """
    if not port_name:
        raise ValueError("port_name is required")
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    com_bytes = (com or "").encode("utf-8")
    name_bytes = str(port_name).encode("utf-8")
    type_bytes = port_type.encode("utf-8")
    body = encode_port_value_binary(port_type, value)

    return (
        _LENGTH.pack(len(com_bytes)) + com_bytes
        + _LENGTH.pack(len(name_bytes)) + name_bytes
        + _LENGTH.pack(len(type_bytes)) + type_bytes
        + body
    )


def unpack_binary_port_value(data: bytes) -> Tuple[str, str, Any, str]:
    """Unpack a record produced by :func:`pack_binary_port_value`.

    :returns: ``(port_name, port_type, value, com)``.
    """
    offset = 0

    def read_field() -> bytes:
        nonlocal offset
        if offset + _LENGTH.size > len(data):
            raise ProtocolError("truncated binary port record")
        (length,) = _LENGTH.unpack_from(data, offset)
        offset += _LENGTH.size
        if offset + length > len(data):
            raise ProtocolError("truncated binary port record")
        field = data[offset:offset + length]
        offset += length
        return field

    com = read_field().decode("utf-8")
    name = read_field().decode("utf-8")
    port_type = read_field().decode("utf-8")
    value = decode_port_value_binary(port_type, data[offset:])
    return name, port_type, value, com


# --------------------------------------------------------------------------------------
# Camera metadata: MSG_CAMERA_LIST -> Coms.cameras()
# --------------------------------------------------------------------------------------

#: Bit widths of Minecraft's packed ``BlockPos`` (see ``BlockPos.asLong``).
_POS_X_BITS = 0x3FFFFFF
_POS_Z_BITS = 0x3FFFFFF
_POS_Y_BITS = 0xFFF


def camera_position_key(pos: Any) -> int:
    """Pack ``[x, y, z]`` exactly like Minecraft's ``BlockPos.asLong()``.

    The mod names each camera stream after this packed position
    (``/camera/<hex>/stream.mjpeg``), so this helper lets a caller build a stream URL from a
    position without asking the game for one.
    """
    if not isinstance(pos, (list, tuple)) or len(pos) != 3:
        raise ValueError("pos must be a sequence of 3 integers, got %r" % (pos,))
    try:
        x, y, z = (int(component) for component in pos)
    except (TypeError, ValueError):
        raise ValueError("pos must contain 3 integers, got %r" % (pos,))
    return ((x & _POS_X_BITS) << 38) | ((z & _POS_Z_BITS) << 12) | (y & _POS_Y_BITS)


def camera_position_hex(pos: Any) -> str:
    """``[x, y, z]`` -> the lower-case hex form used in the HTTP stream paths."""
    return format(camera_position_key(pos), "x")


def camera_stream_path(pos: Any = None) -> str:
    """The default MJPEG path for a camera.

    Per-camera streams live at ``/camera/<packed-pos-hex>/stream.mjpeg``. Without a position the
    global ``/stream.mjpeg`` endpoint is used (a mixed view is planned on the mod side).
    """
    if pos is None:
        return "/stream.mjpeg"
    return "/camera/%s/stream.mjpeg" % camera_position_hex(pos)


def _camera_text(value: Any) -> str:
    return "" if value is None else str(value)


def _camera_number(value: Any, default: float = 0.0) -> float:
    if isinstance(value, bool) or value is None:
        return default
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    if result != result or result in (float("inf"), float("-inf")):  # NaN / infinity
        return default
    return result


def _camera_pos(value: Any) -> Optional[List[int]]:
    """Normalise a ``[x, y, z]`` position, returning ``None`` when it is unusable."""
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        return None
    result: List[int] = []
    for component in value:
        if isinstance(component, bool) or not isinstance(component, (int, float)):
            return None
        try:
            result.append(int(component))
        except (TypeError, ValueError):
            return None
    return result


def _parse_http_url(url: Any) -> Optional[Tuple[str, int, str]]:
    """Split an absolute ``http(s)`` URL into ``(host, port, path)``; ``None`` when unusable."""
    text = _camera_text(url).strip()
    if not text:
        return None
    try:
        parts = urlsplit(text)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    try:
        port = parts.port or (443 if parts.scheme == "https" else 80)
    except ValueError:
        return None
    return parts.hostname, int(port), parts.path or "/"


def _valid_port(port: Any) -> int:
    try:
        value = int(port)
    except (TypeError, ValueError):
        raise ValueError("port must be an integer, got %r" % (port,))
    if not 1 <= value <= 65535:
        raise ValueError("port must be in 1..65535, got %r" % (port,))
    return value


def _resolve_stream_target(
    host: Any, port: Any, path: Optional[str], stream_url: Optional[str], pos: Any
) -> Tuple[str, int, str]:
    """Resolve the (host, port, path) a stream request targets.

    An explicit ``stream_url`` always wins, as the game is authoritative about where its stream
    server lives. Otherwise the request is built from ``host``/``port`` and either ``path`` or the
    camera's packed position.
    """
    if stream_url:
        text = str(stream_url).strip()
        parsed = _parse_http_url(text)
        if parsed is not None:
            return parsed
        if text.startswith("/"):
            return str(host or DEFAULT_CAMERA_HOST), _valid_port(port), text
        raise ValueError("stream_url must be an absolute http(s) URL or a path, got %r"
                         % (stream_url,))

    resolved_host = str(host or DEFAULT_CAMERA_HOST)
    resolved_port = _valid_port(port)
    resolved_path = str(path) if path else camera_stream_path(pos)
    if not resolved_path.startswith("/"):
        resolved_path = "/" + resolved_path
    return resolved_host, resolved_port, resolved_path


def _normalize_camera(
    entry: Any,
    index: int,
    default_host: str,
    default_port: int,
    driver: str,
    driver_available: Optional[bool],
) -> Optional[Dict[str, Any]]:
    if not isinstance(entry, dict):
        return None

    pos = _camera_pos(entry.get("pos"))
    name = _camera_text(entry.get("name")).strip()
    device_name = _camera_text(entry.get("device_name")).strip()
    if not name and not device_name and pos is not None:
        name = "camera@%d,%d,%d" % (pos[0], pos[1], pos[2])
    if not name:
        if not device_name:
            return None
        name = device_name
    if not device_name:
        device_name = name

    width = int(_camera_number(entry.get("width")))
    height = int(_camera_number(entry.get("height")))
    fps = _camera_number(entry.get("fps"))
    status = _camera_text(entry.get("status")).strip().lower()
    if not status:
        status = CAMERA_DRIVER_UNAVAILABLE if driver_available is False else CAMERA_OFFLINE

    entry_driver = _camera_text(entry.get("driver")).strip().lower()
    entry_driver_available = entry.get("driver_available")
    if not isinstance(entry_driver_available, bool):
        entry_driver_available = driver_available

    stream_url = _camera_text(entry.get("stream_url")).strip()
    stream_host = str(default_host or DEFAULT_CAMERA_HOST)
    stream_port = _valid_port(default_port)
    stream_path = ""
    if stream_url:
        parsed = _parse_http_url(stream_url)
        if parsed is not None:
            stream_host, stream_port, stream_path = parsed
    if not stream_path:
        stream_path = camera_stream_path(pos)
        if not stream_url:
            stream_url = "http://%s:%d%s" % (stream_host, stream_port, stream_path)

    camera: Dict[str, Any] = {
        "index": index,
        "name": name,
        "device_name": device_name,
        "pos": pos,
        "width": width,
        "height": height,
        "resolution": ("%dx%d" % (width, height)) if width > 0 and height > 0 else "",
        "fps": fps,
        "status": status,
        "driver": entry_driver or driver,
        "stream_url": stream_url,
        "stream_host": stream_host,
        "stream_port": stream_port,
        "stream_path": stream_path,
    }
    if entry_driver_available is not None:
        camera["driver_available"] = entry_driver_available
    return camera


def normalize_camera_list(
    payload: Any,
    default_host: str = DEFAULT_CAMERA_HOST,
    default_port: int = DEFAULT_CAMERA_PORT,
) -> List[Dict[str, Any]]:
    """Normalise a ``MSG_CAMERA_LIST`` payload into a list of camera dictionaries.

    Tolerant on purpose: the game may omit optional fields and one malformed entry must not take
    the whole list (or the background pump) down. A payload that is structurally wrong raises
    :class:`CameraError`; the client turns that into a regular error callback.
    """
    if not isinstance(payload, dict):
        raise CameraError("camera payload must be a JSON object, got %s" % type(payload).__name__)
    entries = payload.get("cameras")
    if entries is None:
        entries = []
    if not isinstance(entries, (list, tuple)):
        raise CameraError("camera payload 'cameras' must be a list, got %s" % type(entries).__name__)

    driver = _camera_text(payload.get("driver")).strip().lower()
    raw_available = payload.get("driver_available")
    driver_available = raw_available if isinstance(raw_available, bool) else None

    cameras: List[Dict[str, Any]] = []
    for entry in entries:
        camera = _normalize_camera(entry, len(cameras), default_host, default_port, driver,
                                   driver_available)
        if camera is not None:
            cameras.append(camera)
    return cameras


def resolve_camera(cameras: Any, name_or_pos: Any = None) -> Optional[Dict[str, Any]]:
    """Find a camera by name, device name, position, position hex or list index.

    ``None`` selects the first camera. ``int`` is a list index (negative allowed); ``str`` matches
    ``name`` or ``device_name`` (exact first, then case-insensitive) and finally the packed-position
    hex used in stream paths; ``[x, y, z]`` matches ``pos``. Returns ``None`` when not found.
    """
    if not cameras:
        return None
    if name_or_pos is None:
        return cameras[0]
    if isinstance(name_or_pos, bool):
        raise TypeError("camera selector must be a name, position or index, not a bool")
    if isinstance(name_or_pos, int):
        try:
            return cameras[name_or_pos]
        except IndexError:
            return None
    if isinstance(name_or_pos, (list, tuple)):
        wanted = _camera_pos(name_or_pos)
        if wanted is None:
            raise ValueError("camera position must be [x, y, z], got %r" % (name_or_pos,))
        for camera in cameras:
            if camera.get("pos") == wanted:
                return camera
        return None

    wanted_text = str(name_or_pos).strip()
    for camera in cameras:
        if camera.get("name") == wanted_text or camera.get("device_name") == wanted_text:
            return camera
    lowered = wanted_text.lower()
    for camera in cameras:
        if (str(camera.get("name", "")).lower() == lowered
                or str(camera.get("device_name", "")).lower() == lowered):
            return camera
    for camera in cameras:
        pos = camera.get("pos")
        if pos is not None and camera_position_hex(pos) == lowered:
            return camera
    return None


# --------------------------------------------------------------------------------------
# MJPEG over HTTP: multipart parser (pure, incremental, no socket)
# --------------------------------------------------------------------------------------

_NEED_MORE = object()
_SKIPPED = object()
_FINISHED = object()


def parse_multipart_boundary(content_type: Optional[str]) -> Optional[bytes]:
    """Extract the ``boundary`` from a ``multipart/x-mixed-replace`` Content-Type header.

    Accepts unquoted and quoted values, any parameter order and any casing. Returns ``None`` when
    the header is not a multipart stream or has no usable boundary.
    """
    if not content_type:
        return None
    pieces = str(content_type).split(";")
    if not pieces[0].strip().lower().startswith("multipart/x-mixed-replace"):
        return None
    for piece in pieces[1:]:
        key, separator, value = piece.partition("=")
        if not separator or key.strip().lower() != "boundary":
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if not value:
            return None
        try:
            raw = value.encode("ascii")
        except UnicodeEncodeError:
            return None
        if len(raw) > _MJPEG_BOUNDARY_MAX_LEN or b"\r" in raw or b"\n" in raw:
            return None
        return raw
    return None


def parse_part_headers(raw: bytes) -> Dict[str, str]:
    """Parse one multipart part's header block into a lower-case-keyed dict."""
    headers: Dict[str, str] = {}
    for line in raw.replace(b"\r\n", b"\n").split(b"\n"):
        if not line.strip():
            continue
        key, separator, value = line.partition(b":")
        if not separator:
            continue
        try:
            name = key.strip().decode("ascii").lower()
        except UnicodeDecodeError:
            continue
        headers[name] = value.strip().decode("latin-1")
    return headers


def parse_http_head(raw: bytes) -> Tuple[int, Dict[str, str]]:
    """Parse an HTTP response head into ``(status_code, headers)``.

    :raises CameraStreamError: when the bytes are not an HTTP response at all.
    """
    lines = raw.replace(b"\r\n", b"\n").split(b"\n")
    status_line = lines[0].decode("latin-1", "replace").strip()
    parts = status_line.split(None, 2)
    if len(parts) < 2 or not parts[0].upper().startswith("HTTP/"):
        raise CameraStreamError("not an HTTP response: %r" % status_line)
    try:
        status = int(parts[1])
    except ValueError:
        raise CameraStreamError("malformed HTTP status line: %r" % status_line)
    return status, parse_part_headers(b"\r\n".join(lines[1:]))


def looks_like_jpeg(frame: bytes) -> bool:
    """Cheap structural check: JPEG SOI marker at the start, EOI marker at the end."""
    if len(frame) < 4 or frame[:2] != b"\xff\xd8":
        return False
    return frame.rstrip(b"\r\n \t\x00")[-2:] == b"\xff\xd9"


class MjpegStreamParser:
    """Incremental parser for ``multipart/x-mixed-replace`` MJPEG streams.

    Feed arbitrary byte chunks with :meth:`feed`; every complete JPEG frame is returned as
    ``bytes``. The parser owns no socket and never blocks, so it is driven directly by
    :class:`CameraStream` and can be unit tested with hand-built byte streams or a fake server.

    Robustness rules:

    * any chunking (including one byte at a time and a boundary split across chunks);
    * optional preamble before the first boundary, CRLF or bare-LF line endings;
    * optional ``Content-Length`` (used only to reject oversized parts early);
    * a corrupt part (no JPEG markers, empty, wrong length) is skipped, not fatal;
    * per-frame, per-header-block and total-buffer size guards against a hostile peer.

    :param boundary: value from :func:`parse_multipart_boundary`.
    :param max_frame_size: reject parts larger than this.
    :param max_header_size: reject a part whose header block is larger than this.
    :param max_buffer_size: reject a stream that accumulates more than this without a frame.
    :param validate_jpeg: skip parts that do not look like a complete JPEG.
    """

    def __init__(
        self,
        boundary: bytes,
        max_frame_size: int = MAX_MJPEG_FRAME_SIZE,
        max_header_size: int = MAX_MJPEG_HEADER_SIZE,
        max_buffer_size: int = MAX_MJPEG_BUFFER_SIZE,
        validate_jpeg: bool = True,
    ) -> None:
        if not boundary:
            raise ValueError("boundary is required")
        self.boundary = boundary
        self._delimiter = b"--" + boundary
        self._max_frame_size = int(max_frame_size)
        self._max_header_size = int(max_header_size)
        self._max_buffer_size = int(max_buffer_size)
        self._validate = bool(validate_jpeg)

        self._buffer = bytearray()
        self._state = "boundary"  # boundary | headers | body
        self._content_length: Optional[int] = None
        self.part_headers: Dict[str, str] = {}
        self.finished = False
        #: Diagnostic counters (also handy in tests).
        self.stats: Dict[str, int] = {"frames": 0, "skipped": 0, "bytes": 0}

    def feed(self, data: bytes) -> List[bytes]:
        """Append ``data`` and return every complete frame that became available."""
        if data:
            self._buffer.extend(data)
            self.stats["bytes"] += len(data)

        frames: List[bytes] = []
        while not self.finished:
            outcome = self._extract()
            if outcome is _NEED_MORE:
                break
            if outcome is _FINISHED:
                break
            if outcome is _SKIPPED:
                continue
            frames.append(outcome)  # type: ignore[arg-type]

        self._trim_buffer()
        if len(self._buffer) > self._max_buffer_size:
            raise MjpegProtocolError(
                "multipart buffer exceeded %d bytes without a complete frame"
                % self._max_buffer_size)
        return frames

    def reset(self) -> None:
        """Drop buffered data and wait for the next boundary (used after a reconnect)."""
        self._buffer = bytearray()
        self._state = "boundary"
        self._content_length = None
        self.part_headers = {}
        self.finished = False

    # -- state machine --------------------------------------------------

    def _extract(self) -> Any:
        buf = self._buffer
        while True:
            if self._state == "boundary":
                index = buf.find(self._delimiter)
                if index < 0:
                    return _NEED_MORE
                after = index + len(self._delimiter)
                tail = bytes(buf[after:after + 2])
                if len(tail) < 2:
                    return _NEED_MORE
                if tail == b"--":
                    self.finished = True
                    return _FINISHED
                window = bytes(buf[after:after + 200])
                match = _MJPEG_BOUNDARY_TAIL.match(window)
                if match is None:
                    # A partial line break can span chunks; anything else is malformed.
                    if len(window) < 200 and b"\n" not in window:
                        return _NEED_MORE
                    raise MjpegProtocolError(
                        "malformed multipart boundary line after %r" % self._delimiter)
                del buf[:after + match.end()]
                self._state = "headers"
                continue

            if self._state == "headers":
                index = buf.find(b"\r\n\r\n")
                separator = 4
                lf_index = buf.find(b"\n\n")
                if lf_index >= 0 and (index < 0 or lf_index < index):
                    index, separator = lf_index, 2
                if index < 0:
                    if len(buf) > self._max_header_size:
                        raise MjpegProtocolError(
                            "part header block exceeded %d bytes" % self._max_header_size)
                    return _NEED_MORE
                raw_headers = bytes(buf[:index])
                del buf[:index + separator]
                self.part_headers = parse_part_headers(raw_headers)
                declared = self.part_headers.get("content-length")
                self._content_length = None
                if declared is not None and declared.strip().isdigit():
                    self._content_length = int(declared.strip())
                    if self._content_length > self._max_frame_size:
                        raise MjpegProtocolError(
                            "part declares %d bytes, above the %d byte frame limit"
                            % (self._content_length, self._max_frame_size))
                self._state = "body"
                continue

            # body
            index = buf.find(self._delimiter)
            if index < 0:
                if len(buf) > self._max_frame_size + self._max_header_size:
                    raise MjpegProtocolError(
                        "frame exceeded %d bytes without a following boundary"
                        % self._max_frame_size)
                return _NEED_MORE

            end = index
            if end >= 2 and buf[end - 2:end] == b"\r\n":
                end -= 2
            elif end >= 1 and buf[end - 1:end] == b"\n":
                end -= 1
            frame = bytes(buf[:end])
            del buf[:index]  # leave the delimiter for the boundary state
            self._state = "boundary"

            if len(frame) > self._max_frame_size:
                raise MjpegProtocolError(
                    "frame of %d bytes exceeds the %d byte limit"
                    % (len(frame), self._max_frame_size))
            if self._content_length is not None and len(frame) < self._content_length:
                self.stats["skipped"] += 1
                continue
            if self._validate and not looks_like_jpeg(frame):
                self.stats["skipped"] += 1
                continue
            self.stats["frames"] += 1
            return frame

    def _trim_buffer(self) -> None:
        """Drop bytes that can never become part of a future frame."""
        if self._state != "boundary" or self.finished:
            return
        if self._delimiter in self._buffer:
            return
        keep = len(self._delimiter) + 1
        if len(self._buffer) > keep:
            del self._buffer[:len(self._buffer) - keep]


# --------------------------------------------------------------------------------------
# MJPEG over HTTP: camera stream consumer
# --------------------------------------------------------------------------------------


class _ChunkedDecoder:
    """Incremental HTTP/1.1 ``Transfer-Encoding: chunked`` decoder.

    Streaming HTTP servers usually have no Content-Length, so they frame the body as
    ``<hex size>\\r\\n<chunk data>\\r\\n`` blocks. The multipart parser must see the decoded body;
    otherwise the chunk size lines corrupt the MJPEG boundary scan.
    """

    __slots__ = ("_buffer", "_remaining", "_state")

    def __init__(self) -> None:
        self._buffer = bytearray()
        self._remaining = 0
        self._state = "size"  # size | data | trailer | done

    def feed(self, data: bytes) -> bytes:
        if not data:
            return b""
        self._buffer.extend(data)
        out = bytearray()
        while True:
            if self._state == "size":
                index = self._buffer.find(b"\r\n")
                if index < 0:
                    return bytes(out)
                line = bytes(self._buffer[:index]).split(b";", 1)[0].strip()
                del self._buffer[:index + 2]
                if not line:
                    continue
                try:
                    size = int(line, 16)
                except ValueError:
                    raise MjpegProtocolError("invalid HTTP chunk size %r" % (line,))
                if size == 0:
                    self._state = "done"
                    return bytes(out)
                self._remaining = size
                self._state = "data"
            elif self._state == "data":
                take = min(self._remaining, len(self._buffer))
                if take:
                    out.extend(self._buffer[:take])
                    del self._buffer[:take]
                    self._remaining -= take
                if self._remaining > 0:
                    return bytes(out)
                self._state = "trailer"
            elif self._state == "trailer":
                if len(self._buffer) < 2:
                    return bytes(out)
                del self._buffer[:2]
                self._state = "size"
            else:  # done: ignore anything after the terminating chunk
                return bytes(out)

    def reset(self) -> None:
        self._buffer = bytearray()
        self._remaining = 0
        self._state = "size"


class CameraStream:
    """Consume one camera's MJPEG over HTTP stream (the video side of the camera feature).

    The camera metadata comes over the 25585 protocol (:meth:`Coms.cameras`), but the JPEG frames
    are served by a separate loopback HTTP server. This class is stdlib-only: Pillow/tkinter are
    optional and only improve :meth:`show`.

    The camera can be located several ways: pass a full ``stream_url`` (authoritative, as the game
    knows best), a ``pos`` (packed into ``/camera/<hex>/stream.mjpeg``), a ``path``, or just
    ``host``/``port`` for the global ``/stream.mjpeg`` view. Usually you build it from a
    :meth:`Coms.cameras` entry::

        camera = CameraStream.from_camera(coms.cameras()[0])

        with camera:                                  # open() / close()
            frame = camera.read_frame(timeout=2.0)    # complete JPEG bytes, or None on timeout
            if frame is not None:
                camera.save("latest.jpg")
                camera.show()

    The stream has **one reader**: do not call :meth:`read_frame` from several threads. Calling
    :meth:`close` from another thread is fine and unblocks a pending read.

    :param host: camera HTTP server host, ``127.0.0.1`` by default.
    :param port: camera HTTP server port, ``25586`` by default (mirrors ``CameraStreamUrls``).
    :param device_name: device name used for display; optional.
    :param pos: block position ``[x, y, z]`` of the camera; builds the per-camera stream path.
    :param name: camera display name; optional.
    :param path: explicit request path, overriding the position-derived one.
    :param stream_url: absolute ``http(s)`` URL; when given it wins over host/port/path.
    :param timeout: connect + read timeout in seconds (also the default total read budget).
    :param auto_reconnect: reconnect with bounded backoff when the stream drops.
    :param max_frame_size: per-frame size guard.
    :param validate_jpeg: skip parts that are not complete JPEG frames.
    """

    def __init__(
        self,
        host: str = DEFAULT_CAMERA_HOST,
        port: int = DEFAULT_CAMERA_PORT,
        device_name: Optional[str] = None,
        pos: Any = None,
        name: Optional[str] = None,
        path: Optional[str] = None,
        stream_url: Optional[str] = None,
        timeout: float = DEFAULT_STREAM_TIMEOUT,
        auto_reconnect: bool = True,
        max_frame_size: int = MAX_MJPEG_FRAME_SIZE,
        validate_jpeg: bool = True,
    ) -> None:
        resolved_host, resolved_port, resolved_path = _resolve_stream_target(
            host, port, path, stream_url, pos)
        self.host = resolved_host
        self.port = resolved_port
        self.path = resolved_path
        self.pos = _camera_pos(pos)
        self.name = str(name) if name else None
        if device_name:
            self.device_name = str(device_name)
        elif self.name:
            self.device_name = self.name
        elif self.pos is not None:
            self.device_name = "camera@%d,%d,%d" % (self.pos[0], self.pos[1], self.pos[2])
        else:
            self.device_name = "camera"

        self.timeout = float(timeout)
        if self.timeout <= 0:
            raise ValueError("timeout must be positive, got %r" % (timeout,))
        self.auto_reconnect = bool(auto_reconnect)
        self.max_frame_size = int(max_frame_size)
        self.validate_jpeg = bool(validate_jpeg)

        self._sock: Optional[socket.socket] = None
        self._parser: Optional[MjpegStreamParser] = None
        self._pending: List[bytes] = []
        self._chunked = False
        self._chunk_decoder: Optional[_ChunkedDecoder] = None
        self._last_frame: Optional[bytes] = None
        self._last_error: Optional[str] = None
        self._closed = False
        self._lock = threading.RLock()

        # Display state.
        self._tk_root: Any = None
        self._tk_label: Any = None
        self._temp_frame_path: Optional[str] = None
        self._last_file_show = 0.0
        self._file_show_interval = 1.0
        self.display_mode: Optional[str] = None

    # -- construction helpers ------------------------------------------

    @classmethod
    def from_camera(
        cls,
        camera: Dict[str, Any],
        host: Optional[str] = None,
        port: Optional[int] = None,
        **kwargs: Any
    ) -> "CameraStream":
        """Build a stream from one :meth:`Coms.cameras` entry.

        The entry's ``stream_url`` is used when present; otherwise ``host``/``port`` (or the
        entry's ``stream_host``/``stream_port``) plus its ``pos`` are used. ``name`` and
        ``device_name`` are display keys only: a request path needs ``stream_url`` or ``pos`` and
        falls back to the global ``/stream.mjpeg`` when neither is known. Extra keyword arguments
        are forwarded to the constructor.
        """
        if not isinstance(camera, dict):
            raise CameraStreamError("camera entry must be a mapping, got %r" % (camera,))
        name = camera.get("name")
        device_name = camera.get("device_name")
        pos = camera.get("pos")
        stream_url = camera.get("stream_url")
        if stream_url:
            return cls(stream_url=str(stream_url), name=name, device_name=device_name,
                       pos=pos, **kwargs)
        resolved_host = host if host is not None else (camera.get("stream_host")
                                                       or DEFAULT_CAMERA_HOST)
        raw_port = port if port is not None else (camera.get("stream_port")
                                                  or DEFAULT_CAMERA_PORT)
        try:
            resolved_port = int(raw_port)
        except (TypeError, ValueError):
            raise CameraStreamError("camera entry has an invalid stream_port: %r"
                                    % (camera.get("stream_port"),))
        return cls(host=resolved_host, port=resolved_port, name=name,
                   device_name=device_name, pos=pos, **kwargs)

    @property
    def url(self) -> str:
        """The absolute stream URL this object consumes."""
        return "http://%s:%d%s" % (self.host, self.port, self.path)

    @property
    def last_frame(self) -> Optional[bytes]:
        """The most recently read JPEG frame, or ``None``."""
        return self._last_frame

    @property
    def last_error(self) -> Optional[str]:
        """The most recent stream error, or ``None`` when the last operation succeeded."""
        return self._last_error

    @property
    def closed(self) -> bool:
        """Whether :meth:`close` has been called (the object can be reopened with :meth:`open`)."""
        return self._closed

    @property
    def frame_count(self) -> int:
        """Frames returned by this stream so far."""
        parser = self._parser
        return parser.stats["frames"] if parser is not None else 0

    # -- connection ------------------------------------------------------

    def open(self, timeout: Optional[float] = None) -> "CameraStream":
        """Open the HTTP stream and read the multipart handshake.

        :raises CameraStreamError: on a connection/HTTP/multipart failure.
        """
        self.close()
        self._closed = False
        connect_timeout = self.timeout if timeout is None else float(timeout)
        sock = socket.create_connection((self.host, self.port), timeout=connect_timeout)
        try:
            sock.settimeout(connect_timeout)
            request = (
                "GET %s HTTP/1.1\r\n"
                "Host: %s:%d\r\n"
                "Accept: multipart/x-mixed-replace, */*\r\n"
                "User-Agent: cc_virtual_coms-python\r\n"
                "Connection: keep-alive\r\n"
                "\r\n" % (self.path, self.host, self.port)
            )
            sock.sendall(request.encode("ascii"))

            head = b""
            while b"\r\n\r\n" not in head:
                chunk = sock.recv(4096)
                if not chunk:
                    raise CameraStreamError(
                        "camera server closed the connection during the HTTP handshake")
                head += chunk
                if len(head) > MAX_HTTP_HEAD_SIZE:
                    raise CameraStreamError("camera server sent an oversized HTTP response head")
            raw_head, leftover = head.split(b"\r\n\r\n", 1)
            status, headers = parse_http_head(raw_head)
            if status != 200:
                raise CameraStreamError(
                    "camera stream request failed: HTTP %d" % (status,))
            content_type = headers.get("content-type", "")
            boundary = parse_multipart_boundary(content_type)
            if boundary is None:
                raise CameraStreamError(
                    "camera endpoint is not an MJPEG stream (Content-Type: %r)" % content_type)
        except Exception:
            try:
                sock.close()
            except OSError:
                pass
            raise

        self._sock = sock
        self._parser = MjpegStreamParser(
            boundary,
            max_frame_size=self.max_frame_size,
            validate_jpeg=self.validate_jpeg,
        )
        self._pending = []
        self._last_error = None
        self._chunked = headers.get("transfer-encoding", "").strip().lower() == "chunked"
        self._chunk_decoder = _ChunkedDecoder() if self._chunked else None
        if leftover:
            body = leftover
            if self._chunk_decoder is not None:
                body = self._chunk_decoder.feed(body)
            try:
                if body:
                    self._pending.extend(self._parser.feed(body))
            except Exception:
                # A malformed first chunk must not leak the freshly opened socket.
                self.close()
                raise
        return self

    def close(self) -> None:
        """Close the stream, release the socket and remove the temporary preview frame."""
        self._closed = True
        sock, self._sock = self._sock, None
        self._parser = None
        self._pending = []
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        self._close_tk()
        path, self._temp_frame_path = self._temp_frame_path, None
        if path:
            try:
                os.remove(path)
            except OSError:
                pass

    def __enter__(self) -> "CameraStream":
        return self.open()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # -- reading ---------------------------------------------------------

    def read_frame(self, timeout: Optional[float] = None) -> Optional[bytes]:
        """Return the next complete JPEG frame, or ``None`` when the time budget is exhausted.

        The budget (``timeout``, defaulting to the constructor ``timeout``) bounds a whole call,
        including any reconnect attempts. A dropped connection triggers a bounded-backoff
        reconnect when ``auto_reconnect`` is enabled; otherwise it raises
        :class:`CameraStreamError`. The last, incomplete frame of a connection is discarded.

        :raises CameraStreamError: when the stream is unusable and reconnecting is disabled.
        """
        total = self.timeout if timeout is None else float(timeout)
        deadline = time.monotonic() + max(0.0, total)

        while True:
            with self._lock:
                if self._closed:
                    return None
                if self._pending:
                    frame = self._pending.pop(0)
                    self._last_frame = frame
                    return frame

            if self._sock is None:
                if self._closed:
                    return None
                if not self.auto_reconnect:
                    raise CameraStreamError("camera stream is not open; call open() first")
                if not self._reconnect(deadline):
                    return None
                continue

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None

            try:
                self._sock.settimeout(min(remaining, self.timeout))
                chunk = self._sock.recv(MJPEG_READ_CHUNK)
            except socket.timeout:
                return None
            except OSError as exc:
                self._handle_drop("read failed: %s" % exc)
                if not self.auto_reconnect:
                    raise CameraStreamError("camera stream read failed: %s" % exc)
                continue

            if not chunk:
                self._handle_drop("camera stream closed by the server")
                if not self.auto_reconnect:
                    raise CameraStreamError("camera stream closed by the server")
                continue

            if self._chunk_decoder is not None:
                try:
                    chunk = self._chunk_decoder.feed(chunk)
                except MjpegProtocolError as exc:
                    self._handle_drop(str(exc))
                    if not self.auto_reconnect:
                        raise
                    continue
                if not chunk:
                    continue

            try:
                frames = self._parser.feed(chunk)  # type: ignore[union-attr]
            except MjpegProtocolError as exc:
                self._handle_drop(str(exc))
                if not self.auto_reconnect:
                    raise
                continue
            with self._lock:
                self._pending.extend(frames)

    def _handle_drop(self, reason: str) -> None:
        self._last_error = reason
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        self._parser = None
        self._pending = []
        self._chunked = False
        self._chunk_decoder = None

    def _reconnect(self, deadline: float) -> bool:
        """Try to reopen the stream before ``deadline``; returns whether it succeeded."""
        delay = 0.25
        while not self._closed:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            try:
                self.open(timeout=min(remaining, self.timeout))
                self._last_error = None
                return True
            except (OSError, CameraStreamError) as exc:
                self._last_error = str(exc)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(delay, remaining))
            delay = min(delay * 2.0, 5.0)
        return False

    def __iter__(self) -> Iterator[bytes]:
        """Yield frames until the stream times out or drops (never raises in the iterator)."""
        while True:
            try:
                frame = self.read_frame()
            except CameraStreamError:
                return
            if frame is None:
                return
            yield frame

    # -- saving ----------------------------------------------------------

    def save(self, path: str, frame: Optional[bytes] = None) -> str:
        """Write one raw JPEG frame (default: the most recently read one) to ``path``."""
        payload = frame if frame is not None else self._last_frame
        if payload is None:
            raise CameraStreamError("no frame to save - call read_frame() first")
        directory = os.path.dirname(os.path.abspath(path))
        if directory and not os.path.isdir(directory):
            os.makedirs(directory, exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(payload)
        return path

    def save_frames(
        self,
        directory: str,
        count: int = 10,
        prefix: str = "frame_",
        interval: float = 0.0,
        timeout: Optional[float] = None,
    ) -> List[str]:
        """Read up to ``count`` frames and write them as numbered ``.jpg`` files.

        Best effort: a timeout or a dropped stream keeps the frames already written and records
        the reason in :attr:`last_error` instead of raising.
        """
        os.makedirs(directory, exist_ok=True)
        saved: List[str] = []
        for index in range(int(count)):
            try:
                frame = self.read_frame(timeout=timeout)
            except CameraStreamError as exc:
                self._last_error = str(exc)
                break
            if frame is None:
                break
            path = os.path.join(directory, "%s%05d.jpg" % (prefix, index))
            self.save(path, frame)
            saved.append(path)
            if interval > 0:
                time.sleep(interval)
        return saved

    # -- display ---------------------------------------------------------

    def show(self, frame: Optional[bytes] = None, mode: str = "auto") -> bool:
        """Display one frame and return whether a display path succeeded.

        Two tiers, best first:

        1. ``tk`` + optional Pillow: a live Tk window (the only smooth preview);
        2. a temporary ``.jpg`` handed to the system image viewer (no dependencies).

        ``mode`` is ``"auto"`` (try both), ``"tk"`` or ``"file"``. The system view is a
        *snapshot* path: the viewer is spawned at most once per second, not per frame. This
        method never raises for a missing GUI; it returns ``False`` and sets :attr:`last_error`.
        """
        if mode not in ("auto", "tk", "file"):
            raise ValueError("mode must be 'auto', 'tk' or 'file'")
        payload = frame if frame is not None else self._last_frame
        if payload is None:
            self._last_error = "no frame to show - call read_frame() first"
            return False
        if mode in ("auto", "tk") and self._show_tk(payload):
            self.display_mode = "tk"
            return True
        if mode == "tk":
            return False
        if self._show_file(payload):
            self.display_mode = "file"
            return True
        return False

    def _show_tk(self, frame: bytes) -> bool:
        """Live preview through tkinter, which needs Pillow to decode JPEG."""
        try:
            import tkinter as tk
            from PIL import Image, ImageTk
        except Exception:  # noqa: BLE001 - optional dependencies / no display
            return False
        try:
            if self._tk_root is None or not self._tk_root.winfo_exists():
                root = tk.Tk()
                root.title("cc_virtual_coms - %s" % self.device_name)
                label = tk.Label(root)
                label.pack()
                self._tk_root, self._tk_label = root, label
            image = Image.open(io.BytesIO(frame))
            photo = ImageTk.PhotoImage(image)
            self._tk_label.configure(image=photo)
            self._tk_label.image = photo  # keep a reference; Tk does not own it
            self._tk_root.update()
            return True
        except Exception as exc:  # noqa: BLE001 - no display, TclError, decode error
            self._last_error = "tkinter preview unavailable: %s" % exc
            return False

    def _show_file(self, frame: bytes) -> bool:
        """Write a temporary JPEG and hand it to the system image viewer."""
        try:
            if self._temp_frame_path is None:
                handle, path = tempfile.mkstemp(prefix="cc_virtual_coms_", suffix=".jpg")
                os.close(handle)
                self._temp_frame_path = path
            with open(self._temp_frame_path, "wb") as out:
                out.write(frame)
        except OSError as exc:
            self._last_error = "cannot write preview frame: %s" % exc
            return False

        now = time.monotonic()
        if now - self._last_file_show < self._file_show_interval:
            # The viewer shows a snapshot; do not spawn one per frame.
            return True
        self._last_file_show = now

        try:
            opener = getattr(os, "startfile", None)
            if opener is not None:
                opener(self._temp_frame_path)
                return True
            if sys.platform == "darwin":
                command = ["open", self._temp_frame_path]
            else:
                command = ["xdg-open", self._temp_frame_path]
            subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except (OSError, AttributeError) as exc:
            self._last_error = "no system image viewer: %s" % exc
            return False

    def _close_tk(self) -> None:
        root, self._tk_root = self._tk_root, None
        self._tk_label = None
        if root is not None:
            try:
                root.destroy()
            except Exception:  # noqa: BLE001
                pass

    def __repr__(self) -> str:
        return "<CameraStream %r name=%r connected=%s frames=%d>" % (
            self.url, self.device_name, self._sock is not None, self.frame_count)

    def __del__(self) -> None:
        """Close the socket if the stream was dropped without :meth:`close`."""
        try:
            sock, self._sock = self._sock, None
            if sock is not None:
                sock.close()
        except Exception:  # noqa: BLE001 - never raise from a finaliser
            pass


# --------------------------------------------------------------------------------------
# High level client
# --------------------------------------------------------------------------------------


class VirtualComsClient:
    """A connection to the mod's local virtual coms listener.

    The client keeps a cache of the most recent value of every port the game has pushed, so
    :meth:`read_port` never blocks. Incoming values are dispatched to callbacks registered with
    :meth:`on_data`, or yielded from :meth:`listen`.

    The object can be used as a context manager::

        with VirtualComsClient() as coms:
            coms.write_port("elevator", 12.5)

    :param host: listener address, ``127.0.0.1`` unless the mod was reconfigured.
    :param port: listener TCP port, ``25585`` by default.
    :param auto_reconnect: when true, :meth:`run_forever` reconnects after a drop.
    :param timeout: socket timeout in seconds used by the background pump.
    """

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        auto_reconnect: bool = True,
        timeout: float = 0.5,
    ) -> None:
        self.host = host
        self.port = int(port)
        self.auto_reconnect = auto_reconnect
        self.timeout = timeout

        self._sock: Optional[socket.socket] = None
        self._lock = threading.RLock()
        self._send_lock = threading.Lock()
        # Two threads reading one socket is an error on Windows (WSAEWOULDBLOCK surfaces as
        # BlockingIOError), so all reads are serialised through this lock.
        self._read_lock = threading.RLock()

        self._values: Dict[str, Any] = {}
        self._types: Dict[str, str] = {}
        self._directions: Dict[str, str] = {}
        #: Which serial port (board) each terminal belongs to, as reported by the mod.
        self._coms: Dict[str, str] = {}

        self._data_callbacks: List[Callable[[str, str, Any], None]] = []
        self._error_callbacks: List[Callable[[str], None]] = []
        self._disconnect_callbacks: List[Callable[[], None]] = []

        self._pump_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._last_error: Optional[str] = None

        #: Latest camera snapshot pushed through ``MSG_CAMERA_LIST``. An immutable tuple that the
        #: pump thread replaces atomically, so cache readers need no lock.
        self._cameras: Tuple[Dict[str, Any], ...] = ()
        self._camera_rev: Optional[int] = None
        self._camera_callbacks: List[Callable[[List[Dict[str, Any]]], None]] = []

    # -------------------------------------------------------------- connection

    def connect(self, timeout: float = 5.0) -> "VirtualComsClient":
        """Open the socket. Calling this while already connected is a no-op."""
        with self._lock:
            if self._sock is not None:
                return self

            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            try:
                sock.connect((self.host, self.port))
            except OSError:
                sock.close()
                raise
            sock.settimeout(self.timeout)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self._sock = sock
            self._last_error = None
            return self

    def close(self) -> None:
        """Stop any background pump and close the socket."""
        self._stop_event.set()
        self.stop_background()

        with self._lock:
            sock = self._sock
            self._sock = None

        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    @property
    def connected(self) -> bool:
        """Whether the socket is currently open."""
        with self._lock:
            return self._sock is not None

    def is_connected(self) -> bool:
        """Alias of :attr:`connected`, matching the mod's Lua API naming."""
        return self.connected

    def __enter__(self) -> "VirtualComsClient":
        return self.connect()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # -------------------------------------------------------------- sending

    def send(self, msg_type: int, payload: Optional[dict] = None) -> None:
        """Send one frame.

        :raises NotConnectedError: when there is no live connection.
        """
        self.send_frame(pack_message(msg_type, payload))

    def send_frame(self, frame: bytes) -> None:
        """Send an already packed frame.

        :raises NotConnectedError: when there is no live connection. The message includes whatever
            the game last reported, which is usually the real reason (for example the mod refusing
            the connection because too many are already open).
        """
        with self._send_lock:
            with self._lock:
                sock = self._sock
            if sock is None:
                raise NotConnectedError(self._not_connected_reason())
            try:
                sock.sendall(frame)
            except OSError as exc:
                self._handle_disconnect("send failed: %s" % exc)
                raise NotConnectedError("send failed: %s" % exc)

    def _not_connected_reason(self) -> str:
        """Build a NotConnectedError message that says *why*, not just *that*."""
        message = "not connected to %s:%d" % (self.host, self.port)
        detail = self._last_error
        if detail:
            message += " - the game said: %s" % detail
        else:
            message += (" - the connection is not open. If you create a client in a loop, create it "
                        "once and reuse it: every new client opens another TCP connection until the "
                        "mod's limit is reached.")
        return message

    def send_raw(self, payload: dict, msg_type: int = MSG_DATA_WRITE) -> None:
        """Send an already built payload mapping."""
        self.send(msg_type, payload)

    def write_port(
        self,
        port_name: str,
        value: Any,
        port_type: Optional[str] = None,
        com: Optional[str] = None,
    ) -> None:
        """Write a value into a port on a board in the game.

        :param port_name: terminal name.
        :param value: the value to write.
        :param port_type: one of the four port types; inferred from ``value`` when omitted.
        :param com: optional serial port id to target a specific board.
        :raises ValueError: when the value does not match the port type.
        """
        if port_type is None:
            port_type = self._types.get(port_name) or infer_port_type(value)

        body = pack_port_value(port_name, port_type, value, com=com)
        self.send(MSG_DATA_WRITE, json.loads(body.decode("utf-8")))

    def write_ports(self, com: Optional[str] = None, **values: Any) -> None:
        """Write several ports in one call, inferring each type individually."""
        for name, value in values.items():
            self.write_port(name, value, com=com)

    def heartbeat(self) -> None:
        """Send a liveness probe; the mod echoes it straight back."""
        self.send(MSG_HEARTBEAT)

    def report_error(self, message: str) -> None:
        """Send an error frame to the game, which logs it."""
        self.send(MSG_ERROR, {"error": str(message)})

    # -------------------------------------------------------------- receiving

    def process_message(self, msg_type: int, payload: dict) -> None:
        """Handle one decoded frame, updating the cache and firing callbacks."""
        if msg_type in (MSG_DATA_PUSH, MSG_DATA_WRITE):
            try:
                name, port_type, value = unpack_port_value(payload)
            except ProtocolError as exc:
                self._emit_error(str(exc))
                return
            self._values[name] = value
            self._types[name] = port_type

            # The mod tags every push with the terminal's direction so we can tell which terminals
            # the circuit reads (safe to drive) from the ones the circuit drives (read only).
            direction = payload.get("direction")
            if direction in (DIRECTION_IN, DIRECTION_OUT):
                self._directions[name] = direction

            # Remember which board the terminal lives on, so a write can be addressed correctly
            # without the caller having to track it.
            board = payload.get("com")
            if board:
                self._coms[name] = board

            for callback in list(self._data_callbacks):
                try:
                    callback(name, port_type, value)
                except Exception as exc:  # noqa: BLE001 - user callbacks must not kill the pump
                    self._emit_error("data callback raised: %s" % exc)

        elif msg_type == MSG_ERROR:
            self._emit_error(str(payload.get("error", "unknown error")))

        elif msg_type == MSG_HEARTBEAT:
            pass

        elif msg_type == MSG_CAMERA_LIST:
            self._ingest_camera_list(payload)

    def pump(self, timeout: Optional[float] = None) -> int:
        """Read and dispatch every frame that arrives within ``timeout`` seconds.

        Safe to call from several threads: reads are serialised internally.

        :returns: the number of frames processed, or ``0`` on timeout.
        """
        with self._read_lock:
            return self._pump_locked(timeout)

    def _pump_locked(self, timeout: Optional[float]) -> int:
        with self._lock:
            sock = self._sock
        if sock is None:
            raise NotConnectedError("not connected to %s:%d" % (self.host, self.port))

        previous = sock.gettimeout()
        sock.settimeout(self.timeout if timeout is None else timeout)
        processed = 0
        try:
            while True:
                try:
                    msg_type, payload = read_message(sock)
                except socket.timeout:
                    break
                except EOFError:
                    self._handle_disconnect("connection closed by the game")
                    break
                except ProtocolError as exc:
                    self._emit_error(str(exc))
                    break
                processed += 1
                self.process_message(msg_type, payload)
        finally:
            try:
                sock.settimeout(previous)
            except OSError:
                pass
        return processed

    def listen(self, timeout: Optional[float] = None, max_messages: Optional[int] = None
               ) -> Iterator[Tuple[str, str, Any]]:
        """Yield ``(port, type, value)`` for values pushed by the game.

        :param timeout: total seconds to wait before stopping; ``None`` means forever.
        :param max_messages: stop after this many values.
        """
        deadline = None if timeout is None else time.monotonic() + timeout
        delivered: List[Tuple[str, str, Any]] = []

        def collect(port: str, port_type: str, value: Any) -> None:
            delivered.append((port, port_type, value))

        self.on_data(collect)
        try:
            while True:
                while delivered:
                    yield delivered.pop(0)
                    if max_messages is not None and max_messages <= 0:
                        return
                if deadline is not None and time.monotonic() >= deadline:
                    return
                self.pump(timeout=0.2)
        finally:
            if collect in self._data_callbacks:
                self._data_callbacks.remove(collect)

    # -------------------------------------------------------------- cache access

    def read_port(self, port_name: str, default: Any = None) -> Any:
        """Return the most recently pushed value for ``port_name``.

        The read is served from a local cache, so it never blocks and does not require the game
        to answer. Use :meth:`pump` or :meth:`listen` to keep the cache fresh.
        """
        return self._values.get(port_name, default)

    def port_type(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the last known type of ``port_name``."""
        return self._types.get(port_name, default)

    def port_direction(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the last known direction of ``port_name``: ``"out"``, ``"in"`` or ``default``.

        ``"out"`` terminals are the ones the Cimulink circuit *reads*, so they are the terminals an
        out-of-game program can safely drive. ``"in"`` terminals are driven by the circuit.
        """
        return self._directions.get(port_name, default)

    def port_com(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the serial port id (board) ``port_name`` lives on, e.g. ``"COM1"``.

        The mod reports this on every push, so a program that talks to several boards does not have
        to keep track of which terminal belongs where.
        """
        return self._coms.get(port_name, default)

    def is_writable(self, port_name: str) -> bool:
        """True when ``port_name`` is a known ``"out"`` terminal.

        This is the check to make before driving a terminal: nothing inside the circuit ever
        overwrites an ``out`` terminal, whereas an ``in`` terminal may be rewritten by the circuit
        on the very next game tick.
        """
        return self._directions.get(port_name) == DIRECTION_OUT

    def out_ports(self) -> List[str]:
        """Names of every known ``"out"`` terminal, sorted."""
        return sorted(name for name, direction in self._directions.items() if direction == DIRECTION_OUT)

    def in_ports(self) -> List[str]:
        """Names of every known ``"in"`` terminal, sorted."""
        return sorted(name for name, direction in self._directions.items() if direction == DIRECTION_IN)

    def list_ports(self) -> List[Dict[str, Any]]:
        """Return every port seen so far as ``{"name", "com", "type", "direction", "value"}``."""
        return [
            {
                "name": name,
                "com": self._coms.get(name),
                "type": self._types.get(name),
                "direction": self._directions.get(name),
                "value": value,
            }
            for name, value in sorted(self._values.items())
        ]

    @property
    def values(self) -> Dict[str, Any]:
        """A copy of the whole value cache."""
        return dict(self._values)

    # -------------------------------------------------------------- camera cache access

    @property
    def cameras(self) -> List[Dict[str, Any]]:
        """A copy of the latest camera snapshot pushed by the game (empty for older mods)."""
        return self.list_cameras()

    def list_cameras(self) -> List[Dict[str, Any]]:
        """Every camera reported in the latest ``MSG_CAMERA_LIST`` snapshot.

        Each entry is a plain dict with ``name``, ``device_name``, ``pos``, ``width``, ``height``,
        ``resolution``, ``fps``, ``status``, ``driver``, ``stream_url``, ``stream_host``,
        ``stream_port`` and ``stream_path``. Returns ``[]`` until the game pushes a snapshot;
        reads are served from the background pump cache and never block.
        """
        return [dict(camera) for camera in self._cameras]

    def find_camera(
        self, name_or_pos: Any = None, default: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """Return one camera entry by name, device name, ``[x, y, z]`` position or index.

        ``None`` selects the first camera. Returns ``default`` (``None`` by default) when no
        camera matches, which is also the case when the game is an older build.
        """
        camera = resolve_camera(self._cameras, name_or_pos)
        return camera if camera is not None else default

    @property
    def camera_revision(self) -> Optional[int]:
        """The ``rev`` counter of the latest camera snapshot, or ``None``."""
        return self._camera_rev

    def on_cameras(
        self, callback: Callable[[List[Dict[str, Any]]], None]
    ) -> Callable[[List[Dict[str, Any]]], None]:
        """Register ``callback(cameras)`` for every camera snapshot pushed by the game.

        The callback runs on the background pump thread, so keep it short and thread-safe.
        """
        self._camera_callbacks.append(callback)
        return callback

    def _ingest_camera_list(self, payload: dict) -> None:
        """Normalise and cache a camera snapshot; malformed input becomes an error callback."""
        try:
            cameras = normalize_camera_list(payload)
        except CameraError as exc:
            self._emit_error("invalid camera list: %s" % exc)
            return

        revision = payload.get("rev")
        if isinstance(revision, bool) or not isinstance(revision, (int, float)):
            self._camera_rev = None
        else:
            self._camera_rev = int(revision)

        # Replace the tuple in one assignment: readers never see a half-updated list.
        self._cameras = tuple(cameras)

        for callback in list(self._camera_callbacks):
            try:
                callback(self.list_cameras())
            except Exception as exc:  # noqa: BLE001 - user callbacks must not kill the pump
                self._emit_error("camera callback raised: %s" % exc)

    @property
    def last_error(self) -> Optional[str]:
        """The most recent error reported by the game or by the socket layer."""
        return self._last_error

    # -------------------------------------------------------------- callbacks

    def on_data(self, callback: Callable[[str, str, Any], None]) -> Callable[[str, str, Any], None]:
        """Register ``callback(port_name, port_type, value)`` for pushed values."""
        self._data_callbacks.append(callback)
        return callback

    def on_error(self, callback: Callable[[str], None]) -> Callable[[str], None]:
        """Register ``callback(message)`` for error frames."""
        self._error_callbacks.append(callback)
        return callback

    def on_disconnect(self, callback: Callable[[], None]) -> Callable[[], None]:
        """Register ``callback()`` invoked when the connection drops."""
        self._disconnect_callbacks.append(callback)
        return callback

    def _emit_error(self, message: str) -> None:
        self._last_error = message
        for callback in list(self._error_callbacks):
            try:
                callback(message)
            except Exception:  # noqa: BLE001
                pass

    def _handle_disconnect(self, reason: str) -> None:
        with self._lock:
            sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
            self._emit_error(reason)
            for callback in list(self._disconnect_callbacks):
                try:
                    callback()
                except Exception:  # noqa: BLE001
                    pass

    # -------------------------------------------------------------- background pump

    def start_background(self) -> "VirtualComsClient":
        """Start a daemon thread that continuously pumps incoming frames.

        The thread holds only a **weak** reference to this client, so an abandoned client can be
        garbage collected; its socket is then closed by :meth:`__del__` and the mod releases the
        session. Without that, creating a client in a loop would leak one live connection per
        iteration until the mod's client limit is reached.
        """
        if self._pump_thread is not None and self._pump_thread.is_alive():
            return self
        if not self.connected:
            self.connect()
        self._stop_event.clear()

        reference = weakref.ref(self)

        def pump_loop() -> None:
            while True:
                client = reference()
                if client is None or client._stop_event.is_set():
                    return
                try:
                    if not client.connected:
                        if not client.auto_reconnect:
                            return
                        time.sleep(0.5)
                        try:
                            client.connect()
                        except OSError:
                            # The ``finally`` below drops the strong reference; deleting it here
                            # would make that ``del`` raise UnboundLocalError.
                            continue
                    client.pump(timeout=0.2)
                except NotConnectedError:
                    if not client.auto_reconnect:
                        return
                    time.sleep(0.5)
                except Exception as exc:  # noqa: BLE001 - the pump must stay alive
                    if reference() is None:
                        return
                    client._emit_error("pump error: %s" % exc)
                    time.sleep(0.5)
                finally:
                    # Drop the strong local so an abandoned client becomes collectable; otherwise
                    # this loop would keep it (and its socket) alive forever.
                    del client

        self._pump_thread = threading.Thread(target=pump_loop, name="cc_virtual_coms-pump", daemon=True)
        self._pump_thread.start()
        return self

    def stop_background(self, timeout: float = 2.0) -> None:
        """Stop the background pump thread, if any."""
        thread = self._pump_thread
        self._pump_thread = None
        if thread is not None and thread.is_alive():
            thread.join(timeout=timeout)

    def __del__(self) -> None:
        """Close the socket if the client was dropped without :meth:`close`."""
        try:
            self._stop_event.set()
            sock, self._sock = self._sock, None
            if sock is not None:
                sock.close()
        except Exception:  # noqa: BLE001 - never raise from a finaliser
            pass

    def run_forever(self) -> None:
        """Block, dispatching incoming values, until interrupted or disconnected.

        With ``auto_reconnect`` enabled the client retries the connection every half second.
        """
        try:
            while True:
                if not self.connected:
                    if not self.auto_reconnect:
                        return
                    try:
                        self.connect()
                    except OSError:
                        time.sleep(0.5)
                        continue
                self.pump(timeout=0.5)
        except KeyboardInterrupt:
            return
        finally:
            self.close()


# --------------------------------------------------------------------------------------
# Simple facade: Coms
# --------------------------------------------------------------------------------------


_MISSING = object()

#: Timestamps of recently constructed :class:`Coms` objects, used to spot a client created in a loop.
_recent_client_creations: "collections.deque" = collections.deque()
_loop_warning_issued = False


def _note_client_creation() -> None:
    """Warn once when a program keeps constructing clients instead of reusing one.

    A client created inside a loop opens a fresh TCP connection every iteration and leaves the mod
    juggling many concurrent writers, whose writes arrive out of order - the value the game keeps is
    then whichever write happened to be processed last, not the one sent last. It is also how the
    mod's client limit gets hit. This is a common enough mistake to be worth calling out loudly.
    """
    global _loop_warning_issued

    now = time.monotonic()
    _recent_client_creations.append(now)
    while _recent_client_creations and now - _recent_client_creations[0] > 2.0:
        _recent_client_creations.popleft()

    if len(_recent_client_creations) >= 8 and not _loop_warning_issued:
        _loop_warning_issued = True
        print(
            "\n"
            "  [cc_virtual_coms] %d clients created in the last 2 seconds.\n"
            "  Creating a client in a loop opens a new TCP connection every iteration, so the\n"
            "  game ends up applying writes out of order and the value you see lags badly.\n"
            "\n"
            "  Create ONE client and reuse it:\n"
            "\n"
            "      coms = Coms()          # outside the loop\n"
            "      while True:\n"
            "          coms.send(\"port\", value)\n"
            "\n"
            "  (this message is printed once)\n"
            % len(_recent_client_creations),
            file=sys.stderr,
        )


class Coms:
    """The short, friendly way to talk to the game.

    Wraps :class:`VirtualComsClient` so that a working program is four lines long::

        from cc_virtual_coms import Coms

        coms = Coms()                              # 连接 COM1
        frame = coms.pack("drive", 0.25)           # 打包
        coms.send(frame)                           # 发送
        port, ptype, value = coms.recv()           # 接收并解包
        print(coms.get("sense"))                   # 读取缓存里的最新值

    ``send`` also accepts ``(port, value)`` directly, which packs and sends in one line::

        coms.send("drive", 0.25)

    A background thread pumps the socket, so ``recv`` blocks on an internal queue while
    ``send`` stays usable at the same time.

    :param com: default serial port id (``"COM1"``). ``None`` broadcasts to every board.
    :param host: listener address.
    :param port: listener TCP port.
    :param connect: connect immediately (default). Pass ``False`` to connect later.
    :param background: pump the socket on a daemon thread (default).
    :param timeout: socket timeout in seconds.
    """

    def __init__(
        self,
        com: Optional[str] = "COM1",
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        connect: bool = True,
        background: bool = True,
        timeout: float = 0.5,
    ) -> None:
        self.com = com
        self._background = bool(background)
        self._client = VirtualComsClient(host, port, timeout=timeout)
        self._inbox: "queue.Queue" = queue.Queue()
        self._errors: List[str] = []

        # The callbacks must not close a reference cycle. Storing `self._on_data` (a bound method)
        # would make the client point back at this object, so a Coms dropped by the caller could
        # never be collected - and every abandoned Coms would keep a TCP session open until the mod's
        # client limit was reached. Forwarding through a weak reference keeps the graph one-way:
        # Coms -> client, never the reverse.
        reference = weakref.ref(self)

        def forward_data(port_name: str, port_type: str, value: Any) -> None:
            target = reference()
            if target is not None:
                target._on_data(port_name, port_type, value)

        def forward_error(message: str) -> None:
            target = reference()
            if target is not None:
                target._on_error(message)

        self._client.on_data(forward_data)
        self._client.on_error(forward_error)

        if connect:
            self._client.connect()
            _note_client_creation()
            if background:
                self._client.start_background()

    # -------------------------------------------------------------- internals

    def _on_data(self, port_name: str, port_type: str, value: Any) -> None:
        self._inbox.put((port_name, port_type, value))

    def _on_error(self, message: str) -> None:
        self._errors.append(message)

    @property
    def client(self) -> VirtualComsClient:
        """The underlying :class:`VirtualComsClient`, for advanced use."""
        return self._client

    # -------------------------------------------------------------- 打包

    def pack(self, port_name: str, value: Any, port_type: Optional[str] = None) -> bytes:
        """打包一个端口值为**完整帧**，可直接交给 :meth:`send` 或任意 socket。

        ``port_type`` 的选取顺序：

        1. 显式传入的 ``port_type``；
        2. 游戏已报告过的该端子类型（**优先于按值推断** —— 端子类型是游戏定的，
           按值推断只会推出一个游戏不收的类型）；
        3. 按值推断（``True``→boolean，数字→real，三元组→vec3，四元组→quad）。
        """
        resolved = port_type or self._client.port_type(port_name) or infer_port_type(value)
        # Address the board the terminal actually lives on. Falling back to self.com keeps working
        # for terminals the game has not reported yet.
        board = self._client.port_com(port_name, self.com)
        try:
            payload = json.loads(pack_port_value(port_name, resolved, value, com=board).decode("utf-8"))
        except ValueError as exc:
            known = self._client.port_type(port_name)
            if known is not None and resolved == known and port_type is None:
                raise ValueError(
                    "端子 '%s' 在游戏里是 %s 类型，接受不了 %r。"
                    "（如果你想发别的类型，请显式传 port_type=）" % (port_name, known, value)) from None
            raise
        return pack_message(MSG_DATA_WRITE, payload)

    def pack_payload(self, port_name: str, value: Any, port_type: Optional[str] = None) -> bytes:
        """只打包 JSON 负载（不含帧头），用于自定义传输。"""
        resolved = port_type or self._client.port_type(port_name) or infer_port_type(value)
        board = self._client.port_com(port_name, self.com)
        return pack_port_value(port_name, resolved, value, com=board)

    # -------------------------------------------------------------- 发送

    def send(self, packet: Any, value: Any = _MISSING, port_type: Optional[str] = None) -> int:
        """发送。

        两种用法等价：

        * ``coms.send(frame)``          —— 发送 :meth:`pack` 打包好的帧
        * ``coms.send("drive", 0.25)``  —— 一行完成打包 + 发送

        :returns: 1（发送的帧数）
        """
        if isinstance(packet, (bytes, bytearray, memoryview)):
            self._client.send_frame(bytes(packet))
            return 1
        if value is _MISSING:
            raise TypeError("send(packed_frame) 或 send(port_name, value)")
        self._client.send_frame(self.pack(packet, value, port_type))
        return 1

    def write(self, port_name: str, value: Any, port_type: Optional[str] = None) -> int:
        """``send`` 的别名：``coms.write("drive", 0.25)``。"""
        return self.send(port_name, value, port_type)

    def heartbeat(self) -> None:
        """发送心跳。"""
        self._client.heartbeat()

    # -------------------------------------------------------------- 接收

    def recv(self, timeout: Optional[float] = None) -> Optional[Tuple[str, str, Any]]:
        """接收一条游戏推送的值并解包。

        :returns: ``(port_name, port_type, value)``；超时返回 ``None``。
        """
        try:
            return self._inbox.get(timeout=timeout)
        except queue.Empty:
            return None

    def poll(self, timeout: float = 0.0) -> List[Tuple[str, str, Any]]:
        """把已经到达的推送收下来并解包，返回 ``[(port, type, value), ...]``。

        后台接收模式下只有一个后台线程读 socket，这里只排空内部队列
        （``timeout`` 表示最多等这么久让推送到达）。非后台模式则直接读一次 socket。
        """
        if not self._background:
            self._client.pump(timeout=timeout)
        elif timeout > 0:
            time.sleep(timeout)

        drained = []
        while True:
            try:
                drained.append(self._inbox.get_nowait())
            except queue.Empty:
                return drained

    def recv_all(self, timeout: float = 1.0, max_items: Optional[int] = None) -> List[Tuple[str, str, Any]]:
        """在一段时间内尽量多地接收，返回解包后的列表。"""
        deadline = time.monotonic() + timeout
        collected = []
        while time.monotonic() < deadline:
            item = self.recv(timeout=min(0.1, max(0.0, deadline - time.monotonic())))
            if item is not None:
                collected.append(item)
                if max_items is not None and len(collected) >= max_items:
                    break
        return collected

    def __iter__(self) -> Iterator[Tuple[str, str, Any]]:
        """``for port, ptype, value in coms:`` 持续接收。"""
        while True:
            item = self.recv(timeout=0.2)
            if item is not None:
                yield item

    # -------------------------------------------------------------- 读取

    def get(self, port_name: str, default: Any = None) -> Any:
        """读取该端子最新已知的值（不阻塞，来自本地缓存）。"""
        return self._client.read_port(port_name, default)

    def __getitem__(self, port_name: str) -> Any:
        """``coms["sense"]`` 等价于 ``coms.get("sense")``。"""
        return self._client.read_port(port_name)

    def read(self, port_name: str, default: Any = None, wait: Optional[float] = None) -> Any:
        """读取该端子的值；``wait`` 给定时最多等待这么多秒直到它有值。"""
        if wait is not None and port_name not in self._client.values:
            deadline = time.monotonic() + wait
            while time.monotonic() < deadline and port_name not in self._client.values:
                self.poll(timeout=min(0.1, max(0.0, deadline - time.monotonic())))
        return self._client.read_port(port_name, default)

    # -------------------------------------------------------------- 状态

    @property
    def connected(self) -> bool:
        return self._client.connected

    def is_connected(self) -> bool:
        return self._client.connected

    @property
    def ports(self) -> Dict[str, Any]:
        """最新值缓存 ``{port_name: value}``。"""
        return self._client.values

    def list_ports(self) -> List[Dict[str, Any]]:
        """``[{"name", "type", "direction", "value"}, ...]``。"""
        return self._client.list_ports()

    def out_ports(self) -> List[str]:
        """可安全驱动的 ``out`` 端子（电路只读它们）。"""
        return self._client.out_ports()

    def in_ports(self) -> List[str]:
        """由电路驱动的 ``in`` 端子。"""
        return self._client.in_ports()

    def port_direction(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        return self._client.port_direction(port_name, default)

    def port_com(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """该端子所属电路板的串口号（游戏上报的）。"""
        return self._client.port_com(port_name, default)

    # -------------------------------------------------------------- 图传摄像头

    def cameras(self) -> List[Dict[str, Any]]:
        """游戏上报的图传摄像头列表（非阻塞，读后台泵缓存的快照）。

        每项是普通 dict：``name``、``device_name``、``pos``、``width``、``height``、
        ``resolution``、``fps``、``status``、``driver``、``stream_url``、``stream_host``、
        ``stream_port``、``stream_path``。

        - 列表在客户端连接后由游戏推送（与端子快照同一时机），之后有变化再推；
        - 旧版模组没有相机功能，或尚未收到快照时返回 ``[]``（不会报错）；
        - 非后台模式（``Coms(background=False)``）需要自行 ``coms.client.pump()`` 让快照到达；
        - 拿到条目后可以直接 ``CameraStream.from_camera(entry)`` 开流。
        """
        return self._client.list_cameras()

    def camera_status(
        self, name_or_pos: Any = None, default: Optional[str] = None
    ) -> Optional[str]:
        """返回某个相机的状态字符串：``"streaming"`` / ``"offline"`` / ``"driver_unavailable"``。

        :param name_or_pos: 相机名称、设备名、``[x, y, z]`` 坐标或列表下标；``None`` 取第一个。
        :param default: 未知相机 / 尚未收到快照（例如旧版模组或未连接）时返回的值，默认 ``None``。
        """
        camera = self._client.find_camera(name_or_pos)
        if camera is None:
            return default
        return camera.get("status", default)

    def camera(
        self, name_or_pos: Any = None, default: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """返回单个相机条目（含 ``stream_url`` / ``pos`` 等），未找到时返回 ``default``。"""
        camera = self._client.find_camera(name_or_pos)
        return camera if camera is not None else default

    @property
    def last_error(self) -> Optional[str]:
        """最近一次错误（游戏回传的错误帧，或本地 socket 错误）。"""
        if self._errors:
            return self._errors[-1]
        return self._client.last_error

    def take_errors(self) -> List[str]:
        """取出并清空累积的错误。"""
        errors, self._errors = self._errors, []
        return errors

    def close(self) -> None:
        self._client.close()

    def __del__(self) -> None:
        """Close the connection if the object was dropped without :meth:`close`."""
        try:
            self._client.close()
        except Exception:  # noqa: BLE001 - never raise from a finaliser
            pass

    def __enter__(self) -> "Coms":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def __repr__(self) -> str:
        return "<Coms com=%r %s:%d connected=%s ports=%d cameras=%d>" % (
            self.com, self._client.host, self._client.port,
            self._client.connected, len(self._client.values), len(self._client._cameras))


#: Readable alias.
VirtualComs = Coms

#: Connect to the default listener and return a :class:`Coms`, as a one-liner.
def connect(com: Optional[str] = "COM1", host: str = DEFAULT_HOST,
            port: int = DEFAULT_PORT, **kwargs: Any) -> Coms:
    """``coms = cc_virtual_coms.connect("COM1")``."""
    return Coms(com=com, host=host, port=port, **kwargs)


# --------------------------------------------------------------------------------------
# Command line helper
# --------------------------------------------------------------------------------------


def _main(argv: Optional[List[str]] = None) -> int:
    """Small diagnostic tool: connect, print values, optionally write one."""
    import argparse

    parser = argparse.ArgumentParser(description="CC Synaxis Virtual Coms client")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--write", nargs=2, metavar=("PORT", "VALUE"), action="append",
                        help="write a value before listening (repeatable)")
    parser.add_argument("--com", default=None, help="serial port id to target")
    parser.add_argument("--seconds", type=float, default=10.0, help="how long to listen")
    args = parser.parse_args(argv)

    def on_error(message: str) -> None:
        print("[error] %s" % message)

    with VirtualComsClient(args.host, args.port) as coms:
        coms.on_error(on_error)
        print("connected to %s:%d" % (args.host, args.port))

        for port, raw in (args.write or []):
            try:
                value = json.loads(raw)
            except ValueError:
                value = raw
            coms.write_port(port, value, com=args.com)
            print("-> %s = %r" % (port, value))

        print("listening for %.1fs ..." % args.seconds)
        try:
            for port, port_type, value in coms.listen(timeout=args.seconds):
                print("<- %-20s %-8s %r" % (port, port_type, value))
        except KeyboardInterrupt:
            pass

    return 0


if __name__ == "__main__":
    raise SystemExit(_main())

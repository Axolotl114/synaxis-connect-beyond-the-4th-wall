"""
CC Synaxis Virtual Coms - camera (图传摄像头) example
=====================================================

Shows the two halves of the camera feature:

1. **control plane** - ``Coms.cameras()`` / ``Coms.camera_status()`` list the cameras the game
   reports (name, resolution, fps, device name, stream URL, status);
2. **video plane** - ``CameraStream`` consumes the MJPEG over HTTP fallback stream and gives you
   complete JPEG frames, which you can save (zero dependencies) or preview (Pillow + tkinter
   optional, otherwise the system image viewer).

The MJPEG server is a separate loopback HTTP endpoint, default ``http://127.0.0.1:25586``.
If the game was started with ``-Dcc_virtual_coms.camera.httpPort=<port>``, pass the same value
with ``--camera-port``.

Usage::

    python camera_example.py                          # connect, list cameras, read a few seconds
    python camera_example.py --show                   # additionally preview frames
    python camera_example.py --seconds 10 --save-dir captures
    python camera_example.py --camera-name "Synaxis Camera 1"
    python camera_example.py --camera-pos 100,64,-200
    python camera_example.py --stream-url http://127.0.0.1:25586/camera/abc/stream.mjpeg
    python camera_example.py --offline                # parser demo, no game and no network

Nothing here is required to use the library; ``cc_virtual_coms`` remains stdlib-only and the
Pillow/tkinter preview is strictly optional.
"""

from __future__ import annotations

import argparse
import os
import sys
import time

from cc_virtual_coms import (
    CAMERA_STREAMING,
    DEFAULT_CAMERA_HOST,
    DEFAULT_CAMERA_PORT,
    DEFAULT_HOST,
    DEFAULT_PORT,
    CameraStream,
    CameraStreamError,
    Coms,
    MjpegStreamParser,
    parse_multipart_boundary,
)


def header(title: str) -> None:
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def demo_offline() -> int:
    """Exercise the MJPEG parser without a game: the same code path a live stream uses."""
    header("1. Offline multipart parser demo")

    content_type = 'multipart/x-mixed-replace; boundary="ccvc-demo-boundary"'
    boundary = parse_multipart_boundary(content_type)
    print("Content-Type: %s" % content_type)
    print("parsed boundary: %r" % (boundary,))

    stream = bytearray()
    for index in range(3):
        frame = b"\xff\xd8" + ("demo frame %d " % index).encode() * 6 + b"\xff\xd9"
        stream += b"--" + boundary + b"\r\n"
        stream += b"Content-Type: image/jpeg\r\n"
        stream += b"\r\n"
        stream += frame + b"\r\n"
    stream += b"--" + boundary + b"--\r\n"

    # Feed the bytes in awkward 7-byte chunks, exactly like a socket would deliver them.
    parser = MjpegStreamParser(boundary)
    frames = []
    for offset in range(0, len(stream), 7):
        frames.extend(parser.feed(bytes(stream[offset:offset + 7])))

    print("feed() in 7-byte chunks -> %d complete JPEG frames" % len(frames))
    for index, frame in enumerate(frames):
        print("  frame %d: %d bytes, starts with %s" % (index, len(frame), frame[:2].hex()))
    print("parser stats: %s" % parser.stats)
    print("terminating boundary seen: %s" % parser.finished)
    return 0 if len(frames) == 3 else 1


def _parse_pos(text: str):
    parts = [piece.strip() for piece in text.replace(";", ",").split(",") if piece.strip()]
    if len(parts) != 3:
        raise ValueError("--camera-pos needs three integers, e.g. 100,64,-200")
    return [int(piece) for piece in parts]


def _pick_camera(coms: Coms, name):
    """Wait briefly for the camera snapshot, then pick one entry."""
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline and not coms.cameras():
        time.sleep(0.1)

    cameras = coms.cameras()
    if not cameras:
        return None
    if name is not None:
        return coms.camera(name)
    for camera in cameras:
        if camera.get("status") == CAMERA_STREAMING:
            return camera
    return cameras[0]


def demo_live(args) -> int:
    header("2. Connecting to the game at %s:%d" % (args.host, args.port))
    try:
        coms = Coms(host=args.host, port=args.port, timeout=0.2)
    except OSError as exc:
        print("Could not connect: %s" % exc)
        print()
        print("Check that:")
        print("  * Minecraft is running with this mod loaded;")
        print("  * the listener port is %d (or pass --port);" % args.port)
        print("  * the mod logged 'Virtual coms listener started'.")
        return 1

    try:
        header("3. Camera list (pushed over the 25585 JSON protocol)")
        camera_entry = _pick_camera(coms, args.camera_name)
        cameras = coms.cameras()
        if not cameras:
            print("No cameras reported yet.")
            print("  * older mod build without the camera feature -> no camera message at all;")
            print("  * or no camera transmitter placed/loaded in the world.")
            if not args.stream_url:
                print("Pass --stream-url if you know the MJPEG endpoint and want to try anyway.")
                return 2
        else:
            for camera in cameras:
                print("  %(name)-22s %(status)-18s %(resolution)-10s %(fps)5.1f fps  %(device_name)s"
                      % {"name": camera["name"], "status": camera["status"],
                         "resolution": camera["resolution"] or "-", "fps": camera["fps"],
                         "device_name": camera["device_name"]})
                print("      stream: %s" % camera["stream_url"])

        if args.camera_name or args.camera_pos:
            selector = _parse_pos(args.camera_pos) if args.camera_pos else args.camera_name
            print("\nstatus of selected camera: %r" % (coms.camera_status(selector),))

        header("4. Opening the MJPEG video stream")
        if args.stream_url:
            stream = CameraStream(stream_url=args.stream_url, timeout=args.timeout)
            print("using explicit stream URL: %s" % stream.url)
        elif args.camera_pos:
            stream = CameraStream(host=args.camera_host, port=args.camera_port,
                                  pos=_parse_pos(args.camera_pos), timeout=args.timeout)
            print("using position-derived stream URL: %s" % stream.url)
        elif camera_entry is not None:
            stream = CameraStream.from_camera(camera_entry, timeout=args.timeout)
            print("using camera entry: %s" % stream.url)
        else:
            stream = CameraStream(host=args.camera_host, port=args.camera_port,
                                  timeout=args.timeout)
            print("using default stream URL: %s" % stream.url)

        save_dir = os.path.abspath(args.save_dir)
        os.makedirs(save_dir, exist_ok=True)
        saved = []
        frames = 0
        started = time.monotonic()
        try:
            stream.open()
            while time.monotonic() - started < args.seconds:
                frame = stream.read_frame(timeout=0.5)
                if frame is None:
                    continue
                frames += 1
                if len(saved) < args.save_frames:
                    path = os.path.join(save_dir, "frame_%05d.jpg" % len(saved))
                    saved.append(stream.save(path, frame))
                if args.show:
                    stream.show(frame)      # degrades gracefully; returns False without a GUI
        except CameraStreamError as exc:
            print("stream error: %s" % exc)
            return 3
        finally:
            if stream.last_frame is not None:
                latest = stream.save(os.path.join(save_dir, "latest.jpg"))
                if latest not in saved:
                    saved.append(latest)
            stream.close()

        elapsed = max(time.monotonic() - started, 1e-6)
        print()
        print("read %d frame(s) in %.2fs (~%.1f fps)" % (frames, elapsed, frames / elapsed))
        if saved:
            print("saved %d file(s):" % len(saved))
            for path in saved:
                print("  %s" % path)
        if frames == 0:
            print("No frames arrived. Is the camera streaming (status %r)?"
                  % (coms.camera_status(camera_entry),))
        return 0 if frames > 0 else 4
    finally:
        coms.close()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=DEFAULT_HOST, help="mod TCP listener host")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="mod TCP listener port")
    parser.add_argument("--camera-host", default=DEFAULT_CAMERA_HOST,
                        help="camera HTTP server host (default %s)" % DEFAULT_CAMERA_HOST)
    parser.add_argument("--camera-port", type=int, default=DEFAULT_CAMERA_PORT,
                        help="camera HTTP server port (default %d)" % DEFAULT_CAMERA_PORT)
    parser.add_argument("--camera-name", default=None,
                        help="camera name / device name to select")
    parser.add_argument("--camera-pos", default=None,
                        help="camera block position, e.g. 100,64,-200")
    parser.add_argument("--stream-url", default=None,
                        help="explicit MJPEG URL, e.g. http://127.0.0.1:25586/camera/abc/stream.mjpeg")
    parser.add_argument("--seconds", type=float, default=5.0, help="how long to read (default 5)")
    parser.add_argument("--timeout", type=float, default=5.0,
                        help="connect/read timeout in seconds (default 5)")
    parser.add_argument("--save-dir", default="camera_capture",
                        help="directory for saved frames (default camera_capture)")
    parser.add_argument("--save-frames", type=int, default=0,
                        help="save the first N frames; 0 saves only latest.jpg")
    parser.add_argument("--show", action="store_true",
                        help="preview frames (PIL+tkinter, else system image viewer)")
    parser.add_argument("--offline", action="store_true",
                        help="only run the offline multipart parser demo")
    args = parser.parse_args(argv)

    if args.offline:
        return demo_offline()
    return demo_live(args)


if __name__ == "__main__":
    sys.exit(main())

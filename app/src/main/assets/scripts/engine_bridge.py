import json
import subprocess
import sys


def main():
    if len(sys.argv) < 3:
        print(json.dumps({"ok": False, "error": "Missing arguments"}))
        sys.exit(1)
    ytdlp_bin = sys.argv[1]
    url = sys.argv[2]
    try:
        proc = subprocess.run(
            [ytdlp_bin, "-J", "--no-playlist", url],
            capture_output=True,
            text=True,
            timeout=40,
        )
        if proc.returncode != 0:
            print(json.dumps({"ok": False, "error": proc.stderr.strip() or "Extractor failed"}))
            sys.exit(2)
        data = json.loads(proc.stdout)
        formats = []
        seen = set()
        for f in data.get("formats", []):
            vcodec = f.get("vcodec", "none")
            height = f.get("height")
            fmt_id = f.get("format_id")
            ext = f.get("ext", "mp4")
            if vcodec != "none" and height and fmt_id:
                label = f"{height}p • {str(ext).upper()}"
                if label not in seen:
                    seen.add(label)
                    formats.append({
                        "format_id": str(fmt_id),
                        "label": label,
                        "media_type": "video",
                        "meta": f"Stream {fmt_id}"
                    })
        formats.sort(
            key=lambda x: int(x["label"].split("p")[0])
            if x["label"].split("p")[0].isdigit()
            else 0,
            reverse=True,
        )
        formats.append({"format_id": "gif_clip", "label": "GIF Clip (480w)", "media_type": "gif", "meta": "Animated GIF"})
        formats.append({"format_id": "bestaudio", "label": "Audio Only (MP3)", "media_type": "audio", "meta": "High bitrate audio"})
        print(json.dumps({
            "ok": True,
            "title": data.get("title", "Media Stream"),
            "extractor": data.get("extractor_key", "PythonBridge"),
            "duration": data.get("duration_string", "Live/Stream"),
            "formats": formats,
        }))
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        sys.exit(3)


if __name__ == "__main__":
    main()

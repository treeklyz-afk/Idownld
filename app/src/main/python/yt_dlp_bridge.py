"""Bundled yt-dlp bridge for the Android app. Dependencies are embedded at build time."""
import json
import os
import re
import time
from yt_dlp import YoutubeDL

PREVIEW_LIMIT = 100


def _simple_info(info):
    result = {
        "title": str(info.get("title") or "Media")[:240],
        "extractor_key": str(info.get("extractor_key") or info.get("extractor") or "yt-dlp")[:100],
        "duration": info.get("duration"),
        "duration_string": str(info.get("duration_string") or "Unknown")[:60],
        "thumbnail": str(info.get("thumbnail") or "")[:2048],
        "formats": [],
        "entries": [],
    }
    for item in (info.get("formats") or [])[:250]:
        if not isinstance(item, dict):
            continue
        result["formats"].append({
            "format_id": str(item.get("format_id") or "")[:80],
            "vcodec": str(item.get("vcodec") or "none")[:80],
            "acodec": str(item.get("acodec") or "none")[:80],
            "height": item.get("height") or 0,
            "ext": str(item.get("ext") or "unknown")[:20],
            "fps": item.get("fps") or 0,
            "filesize": item.get("filesize"),
            "filesize_approx": item.get("filesize_approx"),
            "abr": item.get("abr") or 0,
            "url": str(item.get("url") or "")[:4096],
        })
    entries = info.get("entries") or []
    try:
        for index, entry in enumerate(entries):
            if index >= PREVIEW_LIMIT:
                break
            if not isinstance(entry, dict):
                continue
            result["entries"].append({
                "id": str(entry.get("id") or entry.get("url") or index + 1)[:160],
                "title": str(entry.get("title") or entry.get("id") or "Untitled")[:240],
                "playlist_index": entry.get("playlist_index") or index + 1,
                "webpage_url": str(entry.get("webpage_url") or entry.get("url") or "")[:2048],
            })
    except Exception:
        pass
    return result


def analyze(url):
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": "in_playlist",
        "playlistend": PREVIEW_LIMIT,
        "noplaylist": False,
        "ignoreerrors": False,
    }
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
    if not isinstance(info, dict):
        raise RuntimeError("yt-dlp could not extract media information")
    return json.dumps(_simple_info(info), ensure_ascii=False, separators=(",", ":"))


def download(url, format_id, output_dir, playlist_indices_csv="", playlist_mode=False, progress_file_path="", pause_marker_path=""):
    os.makedirs(output_dir, exist_ok=True)
    if format_id == "bestaudio":
        selected_format = "bestaudio"
    elif format_id == "best":
        # Use a muxed single-file source: this build has the FFmpeg JNI library,
        # but does not expose an ffmpeg executable to yt-dlp post-processors.
        selected_format = "best[vcodec!=none][acodec!=none]"
    elif re.fullmatch(r"best(480|720|1080|1440|2160)", format_id or ""):
        height = format_id[4:]
        selected_format = "best[vcodec!=none][acodec!=none][height<=%s]" % height
    elif re.fullmatch(r"[A-Za-z0-9._+-]{1,80}", format_id or ""):
        selected_format = format_id
    else:
        raise ValueError("Invalid yt-dlp format selection")

    def progress_hook(data):
        if not progress_file_path:
            return
        info = data.get("info_dict") or {}
        snapshot = {
            "status": data.get("status", "downloading"),
            "downloaded": int(data.get("downloaded_bytes") or 0),
            "total": int(data.get("total_bytes") or data.get("total_bytes_estimate") or 0),
            "filename": str(info.get("filename") or info.get("title") or "Media")[:240],
        }
        def publish_progress():
            temporary = progress_file_path + ".tmp"
            try:
                with open(temporary, "w", encoding="utf-8") as stream:
                    json.dump(snapshot, stream, separators=(",", ":"))
                os.replace(temporary, progress_file_path)
            except OSError:
                pass
        while pause_marker_path and os.path.exists(pause_marker_path):
            snapshot["status"] = "paused"
            publish_progress()
            time.sleep(0.4)
        publish_progress()

    options = {
        "quiet": True,
        "no_warnings": True,
        "format": selected_format,
        "outtmpl": os.path.join(output_dir, "%(title).100B-%(id)s.%(ext)s"),
        "restrictfilenames": True,
        "noplaylist": not playlist_mode,
        "continuedl": True,
        "socket_timeout": 30,
        "concurrent_fragment_downloads": 4,
        "progress_hooks": [progress_hook],
    }
    if playlist_indices_csv:
        indices = [part.strip() for part in playlist_indices_csv.split(",") if part.strip()]
        if not indices or any(not item.isdigit() for item in indices):
            raise ValueError("Invalid playlist item selection")
        options["playlist_items"] = ",".join(indices)
        options["playlistend"] = max(int(item) for item in indices)
    with YoutubeDL(options) as ydl:
        code = ydl.download([url])
    if code not in (None, 0):
        raise RuntimeError("yt-dlp download failed with code %s" % code)
    new_files = []
    for name in sorted(os.listdir(output_dir)):
        path = os.path.realpath(os.path.join(output_dir, name))
        if not name.startswith(".") and not name.endswith((".part", ".ytdl", ".tmp")) and os.path.isfile(path) and os.path.commonpath([os.path.realpath(output_dir), path]) == os.path.realpath(output_dir):
            new_files.append(path)
    if not new_files:
        raise RuntimeError("yt-dlp finished without producing a media file")
    return json.dumps(new_files, ensure_ascii=False)

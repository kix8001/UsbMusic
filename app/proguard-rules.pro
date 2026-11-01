# Media3 / Compose は各ライブラリ同梱のルールで動作します。
# FFmpeg の音声デコーダは名前で読み込まれるので残す
-keep class androidx.media3.decoder.ffmpeg.** { *; }

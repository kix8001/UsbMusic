# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 概要

アプリ名は **mTunes**。車載Androidナビ PR-JM200（Android 14、11.8インチ横画面）で、USBメモリ内の音楽を再生するための個人用アプリ。Kotlin + Jetpack Compose + Media3（ExoPlayer）、単一モジュール `:app`、パッケージ `app.usbmusic.player`。

- **UIの目標**：Apple CarPlay の「ミュージック」風。アーティスト・検索などの画面もCarPlay風にそろえる。
- **曲情報はIDタグが正**：曲名・曲順・アルバムアートはIDタグから取る。タグはユーザーが Mac の Tag Editor 2 で編集している。フォルダ名やファイル名で推測するのは、タグが無いときの予備に限る。
- **動画再生の目標**：MP4/MKV、AC3・DTS音声、音声・字幕トラックの切り替え、フル画面。停車中だけ映像を出す機能は不要（作らない）。
- ユーザーへの説明は日本語で行う。UI文言・コードコメント・コミットメッセージも日本語。

## ビルドとリリースの流れ

- **このMacにはビルド環境が無い。`./gradlew` などでローカルビルドを試みないこと。** APKのビルドは GitHub Actions（`.github/workflows/build.yml`）で行う。どのブランチへの push でも `assembleRelease` が走り、APKにFFmpegの .so が入っているか確認したうえで、APK を `UsbMusic-apk` アーティファクトとして上げる。
- **修正したら commit して push し、Actions のビルド結果（成功／失敗）まで確認する。** 例：`gh run list --limit 1` で実行を探し、`gh run watch <id> --exit-status` で完了を待つ。失敗したら `gh run view <id> --log-failed` で原因を見て直す（Kotlinのエラーは `e: ` で始まる行）。
- テストは無い（`src/test` も `src/androidTest` も無い）。動作確認はユーザーがナビ実機で行う。
- リリースのたびに `app/build.gradle.kts` の `versionCode` / `versionName` を上げる。コミットメッセージは「mTunes 0.x.y: 変更点…」の形式。
- 署名鍵 `app/usbmusic.jks` とパスワードは意図的にリポジトリに置いている（個人利用・上書きインストール用）。debug/release とも同じ鍵で署名する。

## アーキテクチャ

UI（Activity側）と再生（Service側）はプロセス内で分かれていて、`MediaController` を介してだけやり取りする。

### 再生：`playback/`
- `PlaybackService`（`MediaSessionService`）が ExoPlayer を持つ。音声処理は `VolumeLeveler` → `TeeAudioProcessor(Spectrum)` の順に通す。FFmpeg 拡張（`org.jellyfin.media3:media3-ffmpeg-decoder`）を使うので、AC3/DTS も再生できる。**Media3 と FFmpeg デコーダのバージョンは必ず揃える**（現在は 1.5.0 / 1.5.0+1）。
- キューと再生位置は Service が SharedPreferences に JSON で保存し、起動時に復元する。復元した MediaItem は `MediaItems.withLocal` でURIなどを付け直す。
- ナビのメディアバー向けに、標準の `com.android.music.metachanged` / `playstatechanged` ブロードキャストを送っている。アルバムアートは `ArtLoader.ensureFile` でファイル化してメタデータに付ける。
- `PlaybackOptions`（`VolumeLeveler.kt` 内の object）は、設定値を Service 側の音声処理に渡すためのグローバル状態。`Spectrum` も object で、`ui/Visualizer.kt` がここから直接読む。
- `PlayerConnection` は Activity 側の `MediaController` ラッパー。Compose から観察する再生状態を持つ。

### ライブラリ：`data/`
- `LibraryRepository.scan()` が曲を集める。経路は2つ：
  - `Prefs.MODE_AUTO`：MediaStore を使う。USBが未登録なら `scanFilePaths` でUSBのパスを直接走査し、`MainViewModel.requestMediaScan` で Android に登録を依頼する。
  - `MODE_FOLDER`：SAF のツリーURIを直接たどる。
- 読み込みは2段階。①まず MediaStore などで一覧を作って表示し、②`enrich()` で `TagReader`（自前のIDタグ解析）が読み仮名・ディスク／トラック番号などを補う。結果は `filesDir/library.json` にキャッシュし（`Track.tagsRead` で読み込み済みか判定）、次回はキャッシュを即表示してから再スキャンする。
- `Library` はトラックからアーティスト／アルバムの索引を作る。並び順は `SortKey` で決める（先頭の "The " を無視、かな／カナ・全角／半角を同一視、邦楽→洋楽、索引文字の算出）。
- `MainViewModel` は MediaStore に ContentObserver を登録していて、USBの抜き挿しで自動的に再スキャンする（3秒デバウンス、スキャン中は終わってから1回だけ再実行）。

### UI：`ui/`
- `App.kt` がサイドバーと画面切り替えを担う（ナビゲーションライブラリは使っていない）。
- `Visualizer.kt` には再生中画面のビジュアライザー（`VisStyle`）が多数ある。VFD 3D はドットフォント PixelMplus12（`assets/fonts`）で描画する。
- `Video.kt` は動画の一覧と再生（クロスディゾルブ、冒頭タイトル表示、途中から再開＝`data/VideoPositions`）。
- `Voice.kt` は音声操作。`MEDIA_PLAY_FROM_SEARCH` インテントは `MainActivity` で受ける。

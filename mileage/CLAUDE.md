# CLAUDE.md

このリポジトリで作業する Claude Code 向けの説明。

## 概要

アプリ名は **走行距離ログ**。車の走行距離をスマホの GPS で記録し、ガソリン代の支給申請（通勤 18円/km・業務 36円/km）に使う個人用アプリ。Android 版と iPhone 版があり、機能は同じにそろえる。

- `android/`：Kotlin + Jetpack Compose、パッケージ `app.mileage.log`、単一モジュール `:app`
- `ios/`：SwiftUI（iOS 16 以上）。Xcode プロジェクトは `ios/project.yml` から XcodeGen で生成する（`.xcodeproj` はリポジトリに入れない）
- 機能を足すときは**両方に同じものを入れる**。
- ユーザーへの説明は日本語で行う。UI文言・コードコメント・コミットメッセージも日本語。

## ビルドとリリースの流れ

- **ローカルでビルドしない**（ユーザーの Mac に Android SDK も Xcode も無い）。ビルドは GitHub Actions で行う。
  - `.github/workflows/android.yml`：`android/` を変えて push すると `assembleRelease` → アーティファクト `Mileage-android-apk`
  - `.github/workflows/ios.yml`：`ios/` を変えて push すると署名なしでビルド → アーティファクト `Mileage-ios-unsigned-ipa`。Apple シリコン版の macOS 環境は混んで始まらないことがあるので `macos-15-intel` を使う
- 修正したら commit して push し、Actions のビルド結果（成功／失敗）まで確認する。
- テストは無い。動作確認はユーザーが実機で行う。
- リリースのたびにバージョンを上げる：Android は `android/app/build.gradle.kts` の `versionCode` / `versionName`、iOS は `ios/project.yml` の `CFBundleShortVersionString` / `CFBundleVersion`。両方そろえる。コミットメッセージは「走行距離ログ 0.x.y: 変更点…」の形式。
- Android の署名鍵 `android/app/mileage.jks` とパスワードは意図的にリポジトリに置いている（個人利用・上書きインストール用）。
- Claude の環境からは `.github/workflows/` のファイルを push できない（GitHub の workflow 権限が無い）。ワークフローを変えるときは、ユーザーにブラウザで編集してもらうか、Mac から push してもらう。

## アーキテクチャ（両方とも同じ構成）

- **データ**：`Store`（Android は `Store.kt`、iOS は `Store.swift`）が走行記録 `Trip`・メーター記録 `OdoEntry`・設定 `Settings` を `data.json` に保存する。古いデータに無い項目があっても読めるようにしておく（Android は `opt*`、iOS は `decodeIfPresent`）。
  - `Trip` は区分 `Category`（通勤／業務／私用／未分類）、行き先・用件・出発地・到着地の住所と緯度経度を持つ。
  - 住所は保存後に逆ジオコーディングで埋める（Android `Geo.kt`、iOS `Store.fillMissingAddresses`）。ネットが無ければ次回起動時にやり直す。
- **計測**：Android は `TrackingService`（位置情報のフォアグラウンドサービス）、iOS は `Tracker`（`CLLocationManager`、バックグラウンド位置情報）。
  - GPS のゆらぎ・飛びを捨てて距離を足していく（精度 25m 超・時速 250km 超・8m 未満の移動は使わない）。
  - 計測中に落ちても、保存しておいた開始時刻・距離から再開する。
  - **運転終了の確認**：50m 以上動かない状態が `stopAlertMinutes` 続くと通知と画面で聞く。答えがなければ `autoSaveMinutes` 後に止まった時刻で自動保存。直前に時速 20km 以上だったのに GPS が切れた場合はトンネルとみなして止まった扱いにしない。
- **レポート**：`MonthReport`（Android `Report.kt`、iOS `Report.swift`）が月ごとの支給額を計算し、PDF（A4）と CSV（BOM 付き UTF-8）を作る。距離は1回ごとに 0.1km 単位に四捨五入して合計、金額は区分ごとに「合計距離 × 単価」を円未満四捨五入。レポートに載せるのは通勤・業務だけ。
- **画面**：計測／履歴／レポート／メーターの4タブ。

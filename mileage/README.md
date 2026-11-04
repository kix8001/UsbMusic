# 走行距離ログ

車の走行距離をスマホで記録するアプリ。Android（`android/`、Kotlin + Jetpack Compose）と iPhone（`ios/`、SwiftUI）の2つ。機能は同じ。

## 機能
- **計測**：「開始」を押すと GPS で走行距離を測る。画面を消しても、ほかのアプリ（ナビなど）を使っていても記録が続く。「停止」で履歴に保存（50m 未満は保存しない）。
  - GPS のゆらぎ・飛びは捨てる（精度 25m より悪い点、時速 250km を超える移動、8m 未満の細かい動き）。
  - 計測中にアプリが落ちても、次に起動したとき続きから再開する。
- **履歴**：走行の一覧（月ごとの合計つき）、今月・今年・累計の距離。タップで距離・メモの修正や削除。＋で走行を手入力。共有ボタンで CSV を書き出し（経費精算などに）。
- **メーター**：給油のときなどに車のオドメーターの値を記録。前回からの距離と、同じ期間の GPS 記録の合計を比べられる。

データは端末の中だけに保存する（`data.json`）。

## ビルド
GitHub Actions で行う（`mileage/` 以下を変えて push すると走る）。

**最初に1回だけ**：ワークフローのファイルを `.github/workflows/` に移す（Claude の環境からは権限の都合で置けなかったため）。
```
git mv mileage/workflows/mileage-android.yml mileage/workflows/mileage-ios.yml .github/workflows/
git apply mileage/workflows/build.yml.patch   # 任意：mileage/ だけの変更で mTunes をビルドしないようにする
git commit -m "走行距離ログのワークフローを有効化" && git push
```

- Android：`mileage-android.yml` → アーティファクト `Mileage-android-apk`
- iOS：`mileage-ios.yml` → アーティファクト `Mileage-ios-unsigned-ipa`（署名なし）。
  Xcode プロジェクトは `ios/project.yml` から XcodeGen で生成する。

## iPhone へのインストール
iPhone のアプリは Apple の署名が無いと入らない。どちらかの方法で：
1. **Sideloadly（Mac/Windows）**：署名なし IPA を読み込み、自分の Apple ID で署名して USB でつないだ iPhone に入れる。無料の Apple ID だと 7 日ごとに入れ直しが必要。
2. **Xcode（Mac）**：`brew install xcodegen` → `cd mileage/ios && xcodegen generate` → `Mileage.xcodeproj` を開き、Signing & Capabilities で自分の Team を選んで iPhone に実行。

有料の Apple Developer Program（年 12,980 円）に入れば、TestFlight 配布や 1 年有効の署名もできる。

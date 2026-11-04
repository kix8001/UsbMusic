# 走行距離ログ

車の走行距離をスマホで記録するアプリ。Android（`android/`、Kotlin + Jetpack Compose）と iPhone（`ios/`、SwiftUI）の2つ。機能は同じ。

## 機能
- **計測**：「開始」を押すと GPS で実際に走った道のりを測る（最短距離ではない）。画面を消しても、ほかのアプリ（ナビなど）を使っていても記録が続く。「停止」で履歴に保存（50m 未満は保存しない）。
  - 開始前（計測中でも可）に区分（通勤／業務／私用／未分類）を選ぶ。前回の選択を覚えている。
  - GPS のゆらぎ・飛びは捨てる（精度 25m より悪い点、時速 250km を超える移動、8m 未満の細かい動き）。
  - 計測中にアプリが落ちても、次に起動したとき続きから再開する。
  - 出発地・到着地の住所を GPS の位置から自動で入れる（ネットが必要。つながらなかったときは次に起動したとき入れる）。
- **履歴**：走行の一覧。区分で絞り込める（「未分類」を選んで、あとから通勤・業務・私用に分けられる）。タップで区分・距離・行き先・用件・出発地・到着地・メモを修正、または削除。＋で走行を手入力。
- **レポート**：月ごとの支給額（区分ごとの合計距離 × 単価、円未満四捨五入）。距離は 1 回ごとに 0.1km 単位に四捨五入して合計する。
  - 単価の初期値は通勤 18円/km、業務 36円/km（歯車ボタンで変更、氏名もここで設定）。私用は対象外。
  - 会社に出す月別レポートを **PDF**（A4）と **CSV**（Excel でそのまま開ける）で作り、メールなどで共有できる。載るのは通勤・業務の走行だけ。
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

# 薬剤師電卓（iOS）

SwiftUI製の薬剤師向け電卓アプリ。iOS 16以上。

## 機能（v0.1）
- 患者情報（年齢・性別・身長・体重・Cr・シスタチンC）を一度入れれば全計算で共通利用（端末に保存しない）
- 腎機能：CCr（実体重・理想体重・補正体重）、eGFR（Cr・シスタチンC）、体表面積未補正値、CKD区分
- 体表面積（DuBois・藤本・Mosteller）、BMI、理想体重
- 日数：服用終了日、次回受診までの日数、処方箋の有効期限、休薬サイクル、年齢・月齢
- 数量：残薬調整、PTPシート換算、不均等投与
- 用量：散剤の換算、小児薬用量、体重あたりの用量
- 注射：点滴の滴下速度、γ計算
- バーコード：PTPのGS1データバー・箱のGS1-128・JANを読み取り、PMDAの電子添文を開く。履歴と薬品名メモ

## ビルドとインストール
- `main` 以外も含め、`PharmaCalc/` を変更して push すると GitHub Actions（`.github/workflows/pharmacalc-ios.yml`）が macOS 上で XcodeGen → xcodebuild を実行し、署名なしの `PharmaCalc.ipa` を `PharmaCalc-ipa` アーティファクトとして上げる。
- その IPA を Sideloadly で自分の Apple ID で署名して iPhone に入れる（無料の Apple ID の場合は7日ごとに入れ直し）。
- Mac でローカルに開く場合：`brew install xcodegen && cd PharmaCalc && xcodegen generate` で `PharmaCalc.xcodeproj` ができる。

## ワークフローの設置（初回のみ・手作業）
`PharmaCalc/ci/pharmacalc-ios.yml` の内容を `.github/workflows/pharmacalc-ios.yml` として GitHub 上で作成する（Claude の権限ではワークフローファイルを追加できないため）。

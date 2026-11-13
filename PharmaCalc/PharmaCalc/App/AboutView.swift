import SwiftUI

struct AboutView: View {
    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "-"
        return "\(v)（\(b)）"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("バージョン", value: version)
                }
                Section("ご注意") {
                    Text("計算結果は業務の補助として使う目安です。投与量などは、添付文書・ガイドライン・医師の指示で必ず確認してください。計算の誤りによる結果について、作者は責任を負いません。")
                        .font(.callout)
                }
                Section("個人情報") {
                    Text("患者情報（年齢・体重・検査値など）は端末に保存せず、アプリを終了すると消えます。バーコードの読み取り履歴だけを端末内に保存します。外部へは、添付文書を開くときにPMDAのサイトへGTINを送るだけです。")
                        .font(.callout)
                }
                Section("添付文書") {
                    Text("PTPシートや箱のGS1バーコードからGTINを読み取り、PMDA（医薬品医療機器総合機構）の電子添文ページを開きます。")
                        .font(.callout)
                    Link("PMDA 医療用医薬品 情報検索", destination: PMDA.searchURL)
                }
            }
            .navigationTitle("情報")
        }
    }
}

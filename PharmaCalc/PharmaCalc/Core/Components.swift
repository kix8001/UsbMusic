import SwiftUI
import UIKit

/// 文字列を数値にする（全角数字・カンマにも対応）
func parseNum(_ s: String) -> Double? {
    var t = s.trimmingCharacters(in: .whitespaces)
    let full = "０１２３４５６７８９．，"
    let half = "0123456789.."
    for (f, h) in zip(full, half) { t = t.replacingOccurrences(of: String(f), with: String(h)) }
    t = t.replacingOccurrences(of: ",", with: ".")
    guard !t.isEmpty, let v = Double(t), v.isFinite else { return nil }
    return v
}

func parseInt(_ s: String) -> Int? {
    guard let v = parseNum(s), v >= 0, v == v.rounded() else { return nil }
    return Int(v)
}

/// 小数点以下 digits 桁で表示（末尾の0は残す）
func fmt(_ v: Double, _ digits: Int = 1) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = digits
    f.maximumFractionDigits = digits
    f.roundingMode = .halfUp
    return f.string(from: NSNumber(value: v)) ?? String(v)
}

/// 必要な桁だけ表示（12.50 → 12.5、3.0 → 3）
func fmtTrim(_ v: Double, _ maxDigits: Int = 2) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 0
    f.maximumFractionDigits = maxDigits
    f.roundingMode = .halfUp
    return f.string(from: NSNumber(value: v)) ?? String(v)
}

struct NumField: View {
    let title: String
    @Binding var text: String
    var unit: String = ""
    var integer: Bool = false

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            TextField("—", text: $text)
                .keyboardType(integer ? .numberPad : .decimalPad)
                .multilineTextAlignment(.trailing)
                .font(.body.monospacedDigit())
                .frame(maxWidth: 130)
            if !unit.isEmpty {
                Text(unit)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 52, alignment: .leading)
            }
        }
    }
}

/// よく使う値をワンタップで入れるボタン列
struct QuickButtons: View {
    let values: [Double]
    @Binding var text: String
    var suffix: String = ""

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(values, id: \.self) { v in
                    Button(fmtTrim(v) + suffix) { text = fmtTrim(v) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }
        }
    }
}

/// 計算結果の1行。長押しでコピーできる。
struct ResultRow: View {
    let title: String
    let value: String
    var unit: String = ""
    var note: String? = nil
    var big: Bool = true
    var color: Color? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                Spacer()
                Text(value)
                    .font(big ? .title2.monospacedDigit().bold() : .headline.monospacedDigit())
                    .foregroundStyle(color.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.tint))
                if !unit.isEmpty {
                    Text(unit).font(.callout).foregroundStyle(.secondary)
                }
            }
            if let note {
                Text(note).font(.caption).foregroundStyle(.secondary)
            }
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = "\(title)：\(value)\(unit)"
            } label: {
                Label("コピー", systemImage: "doc.on.doc")
            }
        }
    }
}

struct MissingRow: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Label(text, systemImage: "square.and.pencil").foregroundStyle(.secondary)
    }
}

struct WarningRow: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.callout)
            .foregroundStyle(.orange)
    }
}

extension View {
    /// 電卓画面の共通設定（タイトル・キーボードを閉じるボタン）
    func calcForm(_ title: String) -> some View {
        self
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完了") {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                                        to: nil, from: nil, for: nil)
                    }
                }
            }
    }
}

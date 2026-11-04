import SwiftUI

extension Category {
    var color: Color {
        switch self {
        case .commute: return Color(red: 0.31, green: 0.76, blue: 0.97)
        case .business: return .orange
        case .personal: return .gray
        case .unset: return .red
        }
    }
}

/// 区分を選ぶ切り替えボタン
struct CategoryPicker: View {
    @Binding var selection: Category

    var body: some View {
        Picker("区分", selection: $selection) {
            ForEach(Category.ordered) { c in Text(c.label).tag(c) }
        }
        .pickerStyle(.segmented)
    }
}

/// 一覧に出す小さな区分ラベル
struct CategoryBadge: View {
    let category: Category

    var body: some View {
        Text(category.label)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.black)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(category.color))
    }
}

enum Yen {
    static func format(_ v: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return "¥" + (f.string(from: NSNumber(value: v)) ?? String(v))
    }
}

import Foundation

struct ScanRecord: Codable, Identifiable, Equatable {
    var id = UUID()
    var date: Date
    var gtin: String
    var expiry: String?
    var lot: String?
    var raw: String
    var memo: String = ""
}

/// 読み取り履歴（端末に保存）
final class ScanHistory: ObservableObject {
    @Published var items: [ScanRecord] = [] {
        didSet { save() }
    }

    private let key = "scanHistory"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let list = try? JSONDecoder().decode([ScanRecord].self, from: data) {
            items = list
        }
    }

    /// 追加。同じGTINがあれば薬品名メモを引き継いで先頭に移す
    func add(_ r: GS1Result) {
        guard let gtin = r.gtin else { return }
        var rec = ScanRecord(date: Date(), gtin: gtin, expiry: r.expiry, lot: r.lot, raw: r.raw)
        if let i = items.firstIndex(where: { $0.gtin == gtin }) {
            rec.memo = items[i].memo
            items.remove(at: i)
        }
        items.insert(rec, at: 0)
        if items.count > 300 { items.removeLast(items.count - 300) }
    }

    func memo(for gtin: String) -> String {
        items.first(where: { $0.gtin == gtin })?.memo ?? ""
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

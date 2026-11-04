import Foundation

/// 1回の走行。GPS で測ったもの（manual = false）と手入力したもの（manual = true）がある
struct Trip: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var start: Date
    var end: Date
    var meters: Double
    var note: String = ""
    var manual: Bool = false

    var km: Double { meters / 1000 }
}

/// オドメーター（車のメーター）の記録
struct OdoEntry: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var time: Date
    var km: Double
    var note: String = ""
}

/// 走行記録とメーター記録を Documents/data.json に保存する
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var trips: [Trip] = []
    @Published private(set) var odos: [OdoEntry] = []

    private struct Payload: Codable {
        var trips: [Trip]
        var odos: [OdoEntry]
    }

    private let url: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("data.json")

    private init() {
        guard let data = try? Data(contentsOf: url) else { return }
        do {
            let p = try JSONDecoder().decode(Payload.self, from: data)
            trips = p.trips.sorted { $0.start > $1.start }
            odos = p.odos.sorted { $0.time > $1.time }
        } catch {
            // 壊れていたら退避して空から始める
            let broken = url.deletingLastPathComponent()
                .appendingPathComponent("data.broken.\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: url, to: broken)
        }
    }

    func addTrip(start: Date, end: Date, meters: Double, note: String = "", manual: Bool = false) {
        trips.append(Trip(start: start, end: end, meters: meters, note: note, manual: manual))
        trips.sort { $0.start > $1.start }
        save()
    }

    func updateTrip(_ trip: Trip) {
        guard let i = trips.firstIndex(where: { $0.id == trip.id }) else { return }
        trips[i] = trip
        trips.sort { $0.start > $1.start }
        save()
    }

    func deleteTrip(_ id: UUID) {
        trips.removeAll { $0.id == id }
        save()
    }

    func addOdo(time: Date, km: Double, note: String) {
        odos.append(OdoEntry(time: time, km: km, note: note))
        odos.sort { $0.time > $1.time }
        save()
    }

    func deleteOdo(_ id: UUID) {
        odos.removeAll { $0.id == id }
        save()
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(Payload(trips: trips, odos: odos))
            try data.write(to: url, options: .atomic)
        } catch {
            print("保存に失敗: \(error)")
        }
    }

    /// 表計算ソフトで開ける CSV
    func csv() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = "yyyy/MM/dd HH:mm"
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var s = "開始,終了,距離(km),種別,メモ\n"
        for t in trips.sorted(by: { $0.start < $1.start }) {
            s += "\(f.string(from: t.start)),\(f.string(from: t.end)),\(String(format: "%.2f", t.km)),\(t.manual ? "手入力" : "GPS"),\(q(t.note))\n"
        }
        if !odos.isEmpty {
            s += "\n日時,メーター(km),メモ\n"
            for o in odos.sorted(by: { $0.time < $1.time }) {
                s += "\(f.string(from: o.time)),\(String(format: "%.1f", o.km)),\(q(o.note))\n"
            }
        }
        return s
    }
}

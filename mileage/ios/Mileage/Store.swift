import CoreLocation
import Foundation

/// 走行の区分。通勤・業務はガソリン代の支給対象、私用は対象外
enum Category: String, Codable, CaseIterable, Identifiable {
    case unset = "UNSET", commute = "COMMUTE", business = "BUSINESS", personal = "PRIVATE"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .unset: return "未分類"
        case .commute: return "通勤"
        case .business: return "業務"
        case .personal: return "私用"
        }
    }
    var isClaim: Bool { self == .commute || self == .business }
    /// 選ぶ画面での並び順
    static let ordered: [Category] = [.commute, .business, .personal, .unset]
}

/// 1回の走行。GPS で測ったもの（manual = false）と手入力したもの（manual = true）がある
struct Trip: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var start: Date
    var end: Date
    var meters: Double
    var note: String = ""
    var manual: Bool = false
    var category: Category = .unset
    /// 行き先（訪問先の名前など）
    var destination: String = ""
    /// 用件
    var purpose: String = ""
    /// 出発地・到着地の住所（GPS の位置から自動で入れる。手で直してもよい）
    var startAddr: String = ""
    var endAddr: String = ""
    var startLat: Double?
    var startLon: Double?
    var endLat: Double?
    var endLon: Double?

    var km: Double { meters / 1000 }
    /// 申請に使う距離（0.1km 単位に四捨五入）
    var reportKm: Double { (meters / 100).rounded() / 10 }

    init(start: Date, end: Date, meters: Double, note: String = "", manual: Bool = false,
         category: Category = .unset, destination: String = "", purpose: String = "",
         startAddr: String = "", endAddr: String = "",
         startLat: Double? = nil, startLon: Double? = nil, endLat: Double? = nil, endLon: Double? = nil) {
        self.start = start; self.end = end; self.meters = meters; self.note = note; self.manual = manual
        self.category = category; self.destination = destination; self.purpose = purpose
        self.startAddr = startAddr; self.endAddr = endAddr
        self.startLat = startLat; self.startLon = startLon; self.endLat = endLat; self.endLon = endLon
    }

    // 古いデータに無い項目があっても読めるようにする
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        start = try c.decode(Date.self, forKey: .start)
        end = try c.decode(Date.self, forKey: .end)
        meters = try c.decode(Double.self, forKey: .meters)
        note = try c.decodeIfPresent(String.self, forKey: .note) ?? ""
        manual = try c.decodeIfPresent(Bool.self, forKey: .manual) ?? false
        category = (try? c.decodeIfPresent(Category.self, forKey: .category)) ?? .unset
        destination = try c.decodeIfPresent(String.self, forKey: .destination) ?? ""
        purpose = try c.decodeIfPresent(String.self, forKey: .purpose) ?? ""
        startAddr = try c.decodeIfPresent(String.self, forKey: .startAddr) ?? ""
        endAddr = try c.decodeIfPresent(String.self, forKey: .endAddr) ?? ""
        startLat = try c.decodeIfPresent(Double.self, forKey: .startLat)
        startLon = try c.decodeIfPresent(Double.self, forKey: .startLon)
        endLat = try c.decodeIfPresent(Double.self, forKey: .endLat)
        endLon = try c.decodeIfPresent(Double.self, forKey: .endLon)
    }
}

/// オドメーター（車のメーター）の記録
struct OdoEntry: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var time: Date
    var km: Double
    var note: String = ""
}

struct Settings: Codable, Equatable {
    /// 1km あたりの支給額（円）
    var commuteRate: Double = 18
    var businessRate: Double = 36
    /// レポートに載せる氏名
    var name: String = ""
    /// 次に計測する走行の区分（計測画面で選ぶ）。基本は業務
    var nextCategory: Category = .business
    /// 何分止まっていたら「運転を終了しましたか？」と聞くか（0 = 聞かない）
    var stopAlertMinutes: Int = 3
    /// 聞いてから何分答えがなければ自動で保存するか
    var autoSaveMinutes: Int = 10

    init() {}

    // 古いデータに無い項目があっても読めるようにする
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        commuteRate = try c.decodeIfPresent(Double.self, forKey: .commuteRate) ?? 18
        businessRate = try c.decodeIfPresent(Double.self, forKey: .businessRate) ?? 36
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        nextCategory = (try? c.decodeIfPresent(Category.self, forKey: .nextCategory)) ?? .business
        stopAlertMinutes = try c.decodeIfPresent(Int.self, forKey: .stopAlertMinutes) ?? 3
        autoSaveMinutes = try c.decodeIfPresent(Int.self, forKey: .autoSaveMinutes) ?? 10
    }

    func rate(_ c: Category) -> Double {
        switch c {
        case .commute: return commuteRate
        case .business: return businessRate
        default: return 0
        }
    }
}

/// 走行記録・メーター記録・設定を Documents/data.json に保存する
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var trips: [Trip] = []
    @Published private(set) var odos: [OdoEntry] = []
    @Published var settings = Settings() {
        didSet { if settings != oldValue { save() } }
    }

    private struct Payload: Codable {
        var trips: [Trip]
        var odos: [OdoEntry]
        var settings: Settings?
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
            if let s = p.settings { settings = s }
        } catch {
            // 壊れていたら退避して空から始める
            let broken = url.deletingLastPathComponent()
                .appendingPathComponent("data.broken.\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: url, to: broken)
        }
    }

    func addTrip(_ trip: Trip) {
        trips.append(trip)
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
            let data = try JSONEncoder().encode(Payload(trips: trips, odos: odos, settings: settings))
            try data.write(to: url, options: .atomic)
        } catch {
            print("保存に失敗: \(error)")
        }
    }

    // MARK: - 住所の自動入力

    private var geocoding = false

    /// 住所が空で、位置だけ残っている走行の住所を埋める（ネットが必要。失敗したら次回やり直す）
    func fillMissingAddresses() {
        guard !geocoding else { return }
        var jobs: [(UUID, Bool, CLLocation)] = []
        for t in trips {
            if t.startAddr.isEmpty, let la = t.startLat, let lo = t.startLon {
                jobs.append((t.id, true, CLLocation(latitude: la, longitude: lo)))
            }
            if t.endAddr.isEmpty, let la = t.endLat, let lo = t.endLon {
                jobs.append((t.id, false, CLLocation(latitude: la, longitude: lo)))
            }
        }
        guard !jobs.isEmpty else { return }
        geocoding = true
        geocodeNext(jobs[...])
    }

    // CLGeocoder は同時に1件ずつしか引けないので順番に処理する
    private func geocodeNext(_ jobs: ArraySlice<(UUID, Bool, CLLocation)>) {
        guard let job = jobs.first else {
            geocoding = false
            return
        }
        let (id, isStart, loc) = job
        CLGeocoder().reverseGeocodeLocation(loc, preferredLocale: Locale(identifier: "ja_JP")) { [weak self] marks, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if let p = marks?.first, let i = self.trips.firstIndex(where: { $0.id == id }) {
                    let addr = [p.administrativeArea, p.locality, p.subLocality, p.thoroughfare, p.subThoroughfare]
                        .compactMap { $0 }.joined()
                    if !addr.isEmpty {
                        if isStart, self.trips[i].startAddr.isEmpty { self.trips[i].startAddr = addr }
                        if !isStart, self.trips[i].endAddr.isEmpty { self.trips[i].endAddr = addr }
                        self.save()
                    }
                } else if marks == nil {
                    // ネットが無いなどで失敗：残りは次回
                    self.geocoding = false
                    return
                }
                // 連続で引くと制限されるので少し間をあける
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.geocodeNext(jobs.dropFirst()) }
            }
        }
    }
}

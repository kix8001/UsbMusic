import Foundation

enum JPDate {
    static let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.locale = Locale(identifier: "ja_JP")
        c.timeZone = .current
        return c
    }()

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyy年M月d日(E)"
        return f
    }()

    private static let shortFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "M/d(E)"
        return f
    }()

    static func add(_ d: Date, _ n: Int) -> Date {
        cal.date(byAdding: .day, value: n, to: cal.startOfDay(for: d)) ?? d
    }

    /// a から b までの日数（同じ日なら0）
    static func diff(_ a: Date, _ b: Date) -> Int {
        cal.dateComponents([.day], from: cal.startOfDay(for: a), to: cal.startOfDay(for: b)).day ?? 0
    }

    static func text(_ d: Date) -> String { formatter.string(from: d) }
    static func short(_ d: Date) -> String { shortFormatter.string(from: d) }
    static func isWeekend(_ d: Date) -> Bool { cal.isDateInWeekend(d) }
}

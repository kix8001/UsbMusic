import Foundation

enum Fmt {
    static func km(_ v: Double) -> String { number(v, digits: 1) }
    static func km2(_ v: Double) -> String { number(v, digits: 2) }

    private static func number(_ v: Double, digits: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return f.string(from: NSNumber(value: v)) ?? String(format: "%.\(digits)f", v)
    }

    static func duration(_ sec: TimeInterval) -> String {
        let s = max(0, Int(sec))
        return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private static func df(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = format
        return f
    }
    private static let dayF = df("M月d日(E)")
    private static let timeF = df("H:mm")
    private static let fullF = df("yyyy年M月d日 H:mm")
    private static let monthF = df("yyyy年M月")

    static func day(_ d: Date) -> String { dayF.string(from: d) }
    static func time(_ d: Date) -> String { timeF.string(from: d) }
    static func full(_ d: Date) -> String { fullF.string(from: d) }
    static func month(_ d: Date) -> String { monthF.string(from: d) }

    /// 文字列を km の数値に（全角数字やカンマも受け付ける）
    static func parseKm(_ s: String) -> Double? {
        let half = s.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? s
        let cleaned = half.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)
        guard let v = Double(cleaned), v >= 0 else { return nil }
        return v
    }
}

extension Calendar {
    func monthKey(_ d: Date) -> Int {
        let c = dateComponents([.year, .month], from: d)
        return (c.year ?? 0) * 100 + (c.month ?? 0)
    }
}

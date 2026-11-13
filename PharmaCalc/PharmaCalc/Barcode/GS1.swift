import Foundation

/// GS1バーコード（PTPシートのGS1データバー、元箱のGS1-128など）を解析した結果
struct GS1Result: Equatable {
    var raw: String
    var gtin: String?
    var expiry: String?   // 有効期限（表示用）
    var lot: String?
    var serial: String?
    var quantity: String?
}

enum GS1 {
    private static let gs: Character = "\u{1D}"
    /// 固定長のAI（アプリケーション識別子）と値の桁数
    private static let fixed: [String: Int] = [
        "00": 18, "01": 14, "02": 14, "11": 6, "12": 6, "13": 6,
        "15": 6, "16": 6, "17": 6, "20": 2,
    ]
    /// 可変長のAI（GS区切りまたは末尾まで）
    private static let variable: Set<String> = ["10", "21", "22", "30", "37"]

    static func parse(_ input: String) -> GS1Result {
        var r = GS1Result(raw: input)
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        // シンボル識別子を外す
        for p in ["]e0", "]E0", "]C1", "]d2", "]Q3"] where s.hasPrefix(p) {
            s.removeFirst(p.count)
        }

        // JAN（EAN-13）・GTIN-14 などの数字だけのコード
        if s.allSatisfy({ $0.isASCII && $0.isNumber }), [8, 12, 13, 14].contains(s.count) {
            let g = String(repeating: "0", count: 14 - s.count) + s
            if checkDigitOK(g) {
                r.gtin = g
                return r
            }
        }

        // 「(01)0498…(17)…」のような括弧つき表記
        if s.hasPrefix("(") {
            s = s.replacingOccurrences(of: ")", with: "")
            s = String(s.dropFirst()).replacingOccurrences(of: "(", with: String(gs))
        }

        var i = s.startIndex
        while i < s.endIndex {
            if s[i] == gs { i = s.index(after: i); continue }
            guard let aiEnd = s.index(i, offsetBy: 2, limitedBy: s.endIndex) else { break }
            let ai = String(s[i..<aiEnd])
            if let len = fixed[ai] {
                guard let vEnd = s.index(aiEnd, offsetBy: len, limitedBy: s.endIndex) else { break }
                assign(ai, String(s[aiEnd..<vEnd]), to: &r)
                i = vEnd
            } else if variable.contains(ai) {
                let vEnd = s[aiEnd...].firstIndex(of: gs) ?? s.endIndex
                assign(ai, String(s[aiEnd..<vEnd]), to: &r)
                i = vEnd
            } else {
                break
            }
        }
        return r
    }

    private static func assign(_ ai: String, _ v: String, to r: inout GS1Result) {
        switch ai {
        case "01", "02": if r.gtin == nil { r.gtin = v }
        case "17": r.expiry = dateText(v)
        case "10": r.lot = v
        case "21": r.serial = v
        case "30", "37": r.quantity = v
        default: break
        }
    }

    /// YYMMDD → 2028年3月31日（DDが00なら月末）
    static func dateText(_ v: String) -> String {
        guard v.count == 6, let yy = Int(v.prefix(2)), let mm = Int(v.dropFirst(2).prefix(2)),
              let dd = Int(v.suffix(2)), (1...12).contains(mm) else { return v }
        let year = 2000 + yy
        if dd == 0 { return "\(year)年\(mm)月（月末）" }
        return "\(year)年\(mm)月\(dd)日"
    }

    /// GS1のチェックデジット（モジュラス10）
    static func checkDigitOK(_ g: String) -> Bool {
        let d = g.compactMap { $0.isASCII ? $0.wholeNumberValue : nil }
        guard d.count == g.count, d.count >= 8, let last = d.last else { return false }
        var sum = 0
        for (k, v) in d.dropLast().reversed().enumerated() {
            sum += v * (k % 2 == 0 ? 3 : 1)
        }
        return (10 - sum % 10) % 10 == last
    }
}

enum PMDA {
    /// GTINからPMDAの電子添文ページを開くURL（添文ナビと同じ形式）
    static func url(gtin: String) -> URL? {
        URL(string: "https://www.pmda.go.jp/PmdaSearch/bookSearch/01/\(gtin)")
    }

    /// 医療用医薬品の添付文書等情報検索（名前で探すとき）
    static let searchURL = URL(string: "https://www.pmda.go.jp/PmdaSearch/iyakuSearch/")!
}

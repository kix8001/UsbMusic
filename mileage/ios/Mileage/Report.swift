import UIKit

/// 1か月分の集計。支給対象は通勤と業務だけ
struct MonthReport {
    let year: Int
    let month: Int
    let settings: Settings
    let trips: [Trip]

    init(year: Int, month: Int, trips all: [Trip], settings: Settings) {
        self.year = year
        self.month = month
        self.settings = settings
        let cal = Calendar.current
        trips = all.filter {
            let c = cal.dateComponents([.year, .month], from: $0.start)
            return c.year == year && c.month == month
        }.sorted { $0.start < $1.start }
    }

    var title: String { "\(year)年\(month)月" }

    /// 会社に出す走行（通勤・業務）
    var claimTrips: [Trip] { trips.filter { $0.category.isClaim } }

    func km(_ c: Category) -> Double {
        (trips.filter { $0.category == c }.reduce(0) { $0 + $1.reportKm } * 10).rounded() / 10
    }
    func count(_ c: Category) -> Int { trips.filter { $0.category == c }.count }

    /// 区分ごとの支給額 = その月の合計距離 × 単価（円未満四捨五入）
    func amount(_ c: Category) -> Int { yen(km(c) * settings.rate(c)) }
    var totalAmount: Int { amount(.commute) + amount(.business) }
    func tripAmount(_ t: Trip) -> Int { yen(t.reportKm * settings.rate(t.category)) }

    /// 円未満を四捨五入（小数の誤差で 0.5 が 0.4999… になっても切り上がるよう少し足す）
    private func yen(_ v: Double) -> Int { Int((v + 0.5 + 1e-9).rounded(.down)) }

    static func fmtRate(_ r: Double) -> String {
        r == r.rounded() ? String(Int(r)) : String(format: "%.1f", r)
    }

    private var dir: URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("reports")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    // MARK: - CSV

    func csvFile() throws -> URL {
        let day = DateFormatter(); day.locale = Locale(identifier: "ja_JP"); day.dateFormat = "yyyy/MM/dd"
        let time = DateFormatter(); time.locale = Locale(identifier: "ja_JP"); time.dateFormat = "HH:mm"
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var s = "車両使用 走行距離報告書,\(q(title))\n"
        if !settings.name.isEmpty { s += "氏名,\(q(settings.name))\n" }
        s += "\n日付,出発,到着,区分,出発地,到着地,行き先,用件,距離(km),単価(円/km),金額(円)\n"
        for t in claimTrips {
            let cols: [String] = [
                day.string(from: t.start),
                t.manual ? "" : time.string(from: t.start),
                t.manual ? "" : time.string(from: t.end),
                t.category.label,
                q(t.startAddr), q(t.endAddr), q(t.destination), q(t.purpose),
                String(format: "%.1f", t.reportKm),
                MonthReport.fmtRate(settings.rate(t.category)),
                String(tripAmount(t)),
            ]
            s += cols.joined(separator: ",") + "\n"
        }
        s += "\n区分,回数,距離(km),単価(円/km),支給額(円)\n"
        for c in [Category.commute, .business] {
            s += "\(c.label),\(count(c)),\(String(format: "%.1f", km(c))),\(MonthReport.fmtRate(settings.rate(c))),\(amount(c))\n"
        }
        s += "合計,,\(String(format: "%.1f", km(.commute) + km(.business))),,\(totalAmount)\n"
        let url = dir.appendingPathComponent("走行距離_\(year)年\(month)月.csv")
        // Excel で文字化けしないよう、先頭に BOM を付ける
        try ("\u{FEFF}" + s).write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    // MARK: - PDF

    func pdfFile() throws -> URL {
        let pageW: CGFloat = 595, pageH: CGFloat = 842 // A4（ポイント）
        let left: CGFloat = 36, right: CGFloat = pageW - 36
        let text: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 8.5)]
        let bold: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 8.5)]
        let dayF = DateFormatter(); dayF.locale = Locale(identifier: "ja_JP"); dayF.dateFormat = "M/d(E)"
        let timeF = DateFormatter(); timeF.locale = Locale(identifier: "ja_JP"); timeF.dateFormat = "H:mm"
        let nf = NumberFormatter(); nf.numberStyle = .decimal
        func yen(_ v: Int) -> String { nf.string(from: NSNumber(value: v)) ?? String(v) }

        // 列：日付 / 時刻 / 区分 / 出発地→到着地 / 行き先・用件 / 距離 / 金額
        let xDate = left, xTime = left + 50, xCat = left + 105, xRoute = left + 135
        let xDest = left + 330, xKmR = right - 55, xAmtR = right
        let rowH: CGFloat = 24

        func draw(_ s: String, _ x: CGFloat, _ y: CGFloat, _ a: [NSAttributedString.Key: Any], width: CGFloat? = nil) {
            let str = NSAttributedString(string: s, attributes: a)
            if let w = width {
                let p = NSMutableParagraphStyle(); p.lineBreakMode = .byTruncatingTail
                let m = NSMutableAttributedString(attributedString: str)
                m.addAttribute(.paragraphStyle, value: p, range: NSRange(location: 0, length: m.length))
                m.draw(with: CGRect(x: x, y: y - 9, width: w, height: 12),
                       options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
            } else {
                str.draw(at: CGPoint(x: x, y: y - 9))
            }
        }
        func drawRight(_ s: String, _ x: CGFloat, _ y: CGFloat, _ a: [NSAttributedString.Key: Any]) {
            let w = NSAttributedString(string: s, attributes: a).size().width
            draw(s, x - w, y, a)
        }

        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: pageW, height: pageH))
        let data = renderer.pdfData { ctx in
            var y: CGFloat = 0
            var pageNo = 0
            let lineColor = UIColor(white: 0.7, alpha: 1)
            func hline(_ yy: CGFloat) {
                let p = UIBezierPath(); p.move(to: CGPoint(x: left, y: yy)); p.addLine(to: CGPoint(x: right, y: yy))
                p.lineWidth = 0.5; lineColor.setStroke(); p.stroke()
            }
            func newPage() {
                ctx.beginPage()
                pageNo += 1
                y = 50
                if pageNo == 1 {
                    draw("車両使用 走行距離報告書", left, y, [.font: UIFont.boldSystemFont(ofSize: 16)])
                    drawRight(title, right, y, [.font: UIFont.boldSystemFont(ofSize: 12)])
                    y += 22
                    if !settings.name.isEmpty {
                        draw("氏名：\(settings.name)", left, y, [.font: UIFont.systemFont(ofSize: 11)])
                        y += 18
                    }
                    y += 6
                }
                draw("日付", xDate, y, bold); draw("時刻", xTime, y, bold); draw("区分", xCat, y, bold)
                draw("出発地 → 到着地", xRoute, y, bold); draw("行き先・用件", xDest, y, bold)
                drawRight("距離(km)", xKmR, y, bold); drawRight("金額(円)", xAmtR, y, bold)
                y += 5; hline(y); y += 12
            }

            newPage()
            if claimTrips.isEmpty {
                draw("この月の通勤・業務の走行はありません", left, y, text)
                y += rowH
            }
            for t in claimTrips {
                if y + rowH > pageH - 140 { newPage() }
                draw(dayF.string(from: t.start), xDate, y, text)
                if !t.manual { draw("\(timeF.string(from: t.start))-\(timeF.string(from: t.end))", xTime, y, text) }
                draw(t.category.label, xCat, y, text)
                draw("\(t.startAddr.isEmpty ? "-" : t.startAddr) →", xRoute, y, text, width: xDest - xRoute - 6)
                draw(t.endAddr.isEmpty ? "-" : t.endAddr, xRoute + 8, y + 10, text, width: xDest - xRoute - 14)
                draw(t.destination, xDest, y, text, width: xKmR - 50 - xDest)
                draw(t.purpose, xDest, y + 10, text, width: xKmR - 50 - xDest)
                drawRight(String(format: "%.1f", t.reportKm), xKmR, y, text)
                drawRight(yen(tripAmount(t)), xAmtR, y, text)
                y += rowH - 10; hline(y); y += 10
            }

            // 合計欄
            if y + 120 > pageH - 30 { newPage() }
            y += 10
            let sum: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10.5)]
            let sumB: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 10.5)]
            let xs: [CGFloat] = [left, left + 120, left + 200, left + 300]
            draw("区分", xs[0], y, sumB)
            drawRight("回数", xs[1] + 30, y, sumB)
            drawRight("距離(km)", xs[2] + 50, y, sumB)
            drawRight("単価(円/km)", xs[3] + 60, y, sumB)
            drawRight("支給額(円)", right, y, sumB)
            y += 5; hline(y); y += 15
            for c in [Category.commute, .business] {
                draw(c.label, xs[0], y, sum)
                drawRight(String(count(c)), xs[1] + 30, y, sum)
                drawRight(String(format: "%.1f", km(c)), xs[2] + 50, y, sum)
                drawRight(MonthReport.fmtRate(settings.rate(c)), xs[3] + 60, y, sum)
                drawRight(yen(amount(c)), right, y, sum)
                y += 18
            }
            hline(y - 12)
            y += 4
            draw("合計", xs[0], y, sumB)
            drawRight(String(format: "%.1f", km(.commute) + km(.business)), xs[2] + 50, y, sumB)
            drawRight("¥" + yen(totalAmount), right, y, [.font: UIFont.boldSystemFont(ofSize: 13)])

            let madeF = DateFormatter(); madeF.locale = Locale(identifier: "ja_JP"); madeF.dateFormat = "yyyy年M月d日"
            draw("作成日 " + madeF.string(from: Date()), left, pageH - 30,
                 [.font: UIFont.systemFont(ofSize: 7.5), .foregroundColor: UIColor.gray])
        }
        let url = dir.appendingPathComponent("走行距離_\(year)年\(month)月.pdf")
        try data.write(to: url, options: .atomic)
        return url
    }
}

import SwiftUI

private let dayPresets: [Double] = [7, 14, 21, 28, 30, 35, 42, 56, 60, 84, 90]

struct EndDateView: View {
    @State private var start = Date()
    @State private var days = ""
    @State private var fromNextDay = false

    var body: some View {
        Form {
            Section {
                DatePicker("処方日（調剤日）", selection: $start, displayedComponents: .date)
                Toggle("翌日から服用開始", isOn: $fromNextDay)
                NumField(title: "処方日数", text: $days, unit: "日", integer: true)
                QuickButtons(values: dayPresets, text: $days, suffix: "日")
            }
            if let n = parseInt(days), n > 0 {
                let first = JPDate.add(start, fromNextDay ? 1 : 0)
                let last = JPDate.add(first, n - 1)
                Section("結果") {
                    ResultRow(title: "服用開始日", value: JPDate.text(first), big: false)
                    ResultRow(title: "最終服用日", value: JPDate.text(last), big: false)
                    ResultRow(title: "薬がなくなる日", value: JPDate.text(JPDate.add(last, 1)),
                              note: "この日までに次の受診が必要です", big: false, color: .orange)
                    ResultRow(title: "週換算", value: "\(n / 7)週＋\(n % 7)日", big: false)
                }
            }
        }
        .calcForm("服用終了日")
    }
}

struct NextVisitView: View {
    @State private var today = Date()
    @State private var visit = JPDate.add(Date(), 28)
    @State private var includeToday = true
    @State private var includeVisitDay = false
    @State private var daily = ""
    @State private var remain = ""

    var body: some View {
        Form {
            Section {
                DatePicker("今日", selection: $today, displayedComponents: .date)
                DatePicker("次回受診日", selection: $visit, displayedComponents: .date)
                Toggle("今日の分を含める", isOn: $includeToday)
                Toggle("受診日当日の分も含める", isOn: $includeVisitDay)
            } footer: {
                Text("受診日の朝の分まで必要な場合は「受診日当日の分も含める」をオンにします")
            }
            Section("数量（任意）") {
                NumField(title: "1日量", text: $daily, unit: "錠")
                NumField(title: "残薬", text: $remain, unit: "錠")
            }

            let base = JPDate.diff(today, visit)
            let need = base - (includeToday ? 0 : 1) + (includeVisitDay ? 1 : 0)
            Section("結果") {
                if base < 0 {
                    WarningRow("受診日が今日より前になっています")
                } else {
                    ResultRow(title: "必要日数", value: "\(max(need, 0))", unit: "日",
                              note: "今日から受診日まで \(base)日")
                    if let d = parseNum(daily), d > 0 {
                        let total = Double(max(need, 0)) * d
                        ResultRow(title: "必要数", value: fmtTrim(total), unit: "錠")
                        if let r = parseNum(remain), r > 0 {
                            let lack = max(0, total - r)
                            ResultRow(title: "残薬を引いた必要数", value: fmtTrim(lack), unit: "錠")
                            ResultRow(title: "処方日数にすると", value: "\(Int((lack / d - 1e-9).rounded(.up)))", unit: "日",
                                      note: "端数は切り上げ")
                        }
                    }
                }
            }
        }
        .calcForm("次回受診まで")
    }
}

struct ValidityView: View {
    @State private var issued = Date()

    var body: some View {
        Form {
            Section {
                DatePicker("交付日", selection: $issued, displayedComponents: .date)
            }
            let end = JPDate.add(issued, 3)
            Section {
                ResultRow(title: "有効期限", value: JPDate.text(end), big: false, color: .orange)
                ResultRow(title: "今日から", value: remainingText(end), big: false)
            }
            Section("有効な4日間") {
                ForEach(0..<4, id: \.self) { i in
                    let d = JPDate.add(issued, i)
                    HStack {
                        Text("\(i + 1)日目")
                        Spacer()
                        Text(JPDate.text(d))
                            .foregroundStyle(JPDate.isWeekend(d) ? Color.red : Color.primary)
                    }
                }
            } footer: {
                Text("処方箋の使用期間は交付日を含めて4日以内です（休日も含む）。長期の旅行などの特殊な事情で、医師が処方箋に別の期限を記載した場合はその日まで有効です。祝日は表示に反映していません。")
            }
        }
        .calcForm("処方箋の有効期限")
    }

    private func remainingText(_ end: Date) -> String {
        let n = JPDate.diff(Date(), end)
        if n < 0 { return "期限切れ（\(-n)日超過）" }
        if n == 0 { return "今日まで" }
        return "あと\(n)日"
    }
}

struct CycleView: View {
    @State private var start = Date()
    @State private var onDays = "21"
    @State private var offDays = "7"
    @State private var cycles = "3"

    var body: some View {
        Form {
            Section {
                DatePicker("開始日", selection: $start, displayedComponents: .date)
                NumField(title: "投与日数", text: $onDays, unit: "日", integer: true)
                NumField(title: "休薬日数", text: $offDays, unit: "日", integer: true)
                NumField(title: "クール数", text: $cycles, unit: "クール", integer: true)
            } footer: {
                Text("例：3週投与1週休薬 → 投与21日・休薬7日、2週投与1週休薬 → 14日・7日")
            }
            if let on = parseInt(onDays), let off = parseInt(offDays), let c = parseInt(cycles),
               on > 0, c > 0, c <= 50 {
                Section("合計") {
                    ResultRow(title: "投与日数の合計", value: "\(on * c)", unit: "日")
                    ResultRow(title: "全期間", value: "\((on + off) * c)", unit: "日",
                              note: "最後のクールの休薬期間まで含む")
                }
                ForEach(0..<c, id: \.self) { i in
                    let s = JPDate.add(start, i * (on + off))
                    let e = JPDate.add(s, on - 1)
                    Section("\(i + 1)クール目") {
                        HStack {
                            Text("投与").foregroundStyle(.tint)
                            Spacer()
                            Text("\(JPDate.short(s)) 〜 \(JPDate.short(e))").monospacedDigit()
                        }
                        if off > 0 {
                            HStack {
                                Text("休薬").foregroundStyle(.secondary)
                                Spacer()
                                Text("\(JPDate.short(JPDate.add(e, 1))) 〜 \(JPDate.short(JPDate.add(e, off)))").monospacedDigit()
                            }
                        }
                    }
                }
            }
        }
        .calcForm("休薬期間のある処方")
    }
}

struct AgeView: View {
    @State private var birth = JPDate.cal.date(byAdding: .year, value: -5, to: Date()) ?? Date()
    @State private var base = Date()

    var body: some View {
        Form {
            Section {
                DatePicker("生年月日", selection: $birth, in: ...Date(), displayedComponents: .date)
                DatePicker("基準日", selection: $base, displayedComponents: .date)
            }
            let c = JPDate.cal.dateComponents([.year, .month, .day],
                                              from: JPDate.cal.startOfDay(for: birth),
                                              to: JPDate.cal.startOfDay(for: base))
            if let y = c.year, let m = c.month, let d = c.day, y >= 0, m >= 0, d >= 0 {
                Section("結果") {
                    ResultRow(title: "年齢", value: "\(y)歳\(m)か月\(d)日", big: false)
                    ResultRow(title: "月齢", value: "\(y * 12 + m)", unit: "か月")
                    ResultRow(title: "日齢", value: "\(JPDate.diff(birth, base))", unit: "日")
                    ResultRow(title: "小数の年齢", value: fmt(Double(JPDate.diff(birth, base)) / 365.25, 2), unit: "歳",
                              note: "小児薬用量の計算に使えます")
                }
            } else {
                Section { WarningRow("基準日が生年月日より前です") }
            }
        }
        .calcForm("年齢・月齢")
    }
}

import SwiftUI

private let sheetSizes = [6, 7, 10, 12, 14, 20, 21, 28]

struct RemainingView: View {
    @State private var daily = ""
    @State private var days = ""
    @State private var remain = ""

    var body: some View {
        Form {
            Section {
                NumField(title: "1日量", text: $daily, unit: "錠")
                NumField(title: "処方日数", text: $days, unit: "日", integer: true)
                NumField(title: "残薬", text: $remain, unit: "錠")
            }
            if let d = parseNum(daily), d > 0, let n = parseInt(days), n > 0 {
                let r = max(0, parseNum(remain) ?? 0)
                let covered = Int((r / d + 1e-9).rounded(.down))
                let frac = r - Double(covered) * d
                let adjDays = max(0, n - covered)
                Section {
                    ResultRow(title: "残薬で足りる日数", value: "\(covered)", unit: "日")
                    ResultRow(title: "調整後の処方日数", value: "\(adjDays)", unit: "日", color: .orange)
                    ResultRow(title: "調整後の処方数", value: fmtTrim(Double(adjDays) * d), unit: "錠",
                              note: frac > 0.0001 ? "1日分に満たない残薬 \(fmtTrim(frac))錠 が手元に残ります" : nil)
                } header: {
                    Text("日数で調整")
                }
                Section {
                    ResultRow(title: "必要数 − 残薬", value: fmtTrim(max(0, d * Double(n) - r)), unit: "錠",
                              note: "総数で調整する場合（日数は変えずに数量だけ減らす）")
                } header: {
                    Text("総数で調整")
                }
                if covered >= n {
                    Section { WarningRow("残薬だけで処方日数分が足ります") }
                }
            }
        }
        .calcForm("残薬調整")
    }
}

/// シートの規格を選ぶ部品
struct SheetSizePicker: View {
    @Binding var size: Int
    @Binding var custom: String

    var body: some View {
        Picker("1シートの錠数", selection: $size) {
            ForEach(sheetSizes, id: \.self) { Text("\($0)").tag($0) }
            Text("その他").tag(0)
        }
        if size == 0 {
            NumField(title: "1シートの錠数", text: $custom, unit: "錠", integer: true)
        }
    }
}

func sheetBreakdown(total: Double, size: Int) -> (sheets: Int, rest: Double) {
    let sheets = Int((total / Double(size) + 1e-9).rounded(.down))
    return (sheets, max(0, total - Double(sheets * size)))
}

struct SheetView: View {
    @State private var total = ""
    @State private var daily = ""
    @State private var days = ""
    @State private var useDaily = false
    @State private var size = 10
    @State private var custom = ""

    var body: some View {
        Form {
            Section {
                Picker("入力方法", selection: $useDaily) {
                    Text("総数").tag(false)
                    Text("1日量×日数").tag(true)
                }
                .pickerStyle(.segmented)
                if useDaily {
                    NumField(title: "1日量", text: $daily, unit: "錠")
                    NumField(title: "日数", text: $days, unit: "日", integer: true)
                } else {
                    NumField(title: "総数", text: $total, unit: "錠")
                }
                SheetSizePicker(size: $size, custom: $custom)
            }
            let s = size == 0 ? (parseInt(custom) ?? 0) : size
            let t: Double? = useDaily
                ? (parseNum(daily).flatMap { d in parseInt(days).map { d * Double($0) } })
                : parseNum(total)
            if let t, t > 0, s > 0 {
                let b = sheetBreakdown(total: t, size: s)
                Section("結果") {
                    ResultRow(title: "総数", value: fmtTrim(t), unit: "錠")
                    ResultRow(title: "シート", value: "\(b.sheets)", unit: "シート", color: .orange)
                    ResultRow(title: "バラ", value: fmtTrim(b.rest), unit: "錠")
                    ResultRow(title: "シートで揃えるなら", value: "\(Int((t / Double(s) - 1e-9).rounded(.up)))", unit: "シート",
                              note: "\(Int((t / Double(s) - 1e-9).rounded(.up)) * s)錠（端数を切り上げ）", big: false)
                }
            }
        }
        .calcForm("PTPシート換算")
    }
}

struct UnevenView: View {
    @State private var morning = ""
    @State private var noon = ""
    @State private var evening = ""
    @State private var bedtime = ""
    @State private var days = ""
    @State private var size = 10
    @State private var custom = ""

    var body: some View {
        Form {
            Section("1回量") {
                NumField(title: "朝", text: $morning, unit: "錠")
                NumField(title: "昼", text: $noon, unit: "錠")
                NumField(title: "夕", text: $evening, unit: "錠")
                NumField(title: "寝る前", text: $bedtime, unit: "錠")
            }
            Section {
                NumField(title: "日数", text: $days, unit: "日", integer: true)
                SheetSizePicker(size: $size, custom: $custom)
            }
            let perDay = [morning, noon, evening, bedtime].compactMap(parseNum).reduce(0, +)
            if perDay > 0, let n = parseInt(days), n > 0 {
                let total = perDay * Double(n)
                let s = size == 0 ? (parseInt(custom) ?? 0) : size
                Section("結果") {
                    ResultRow(title: "1日量", value: fmtTrim(perDay), unit: "錠")
                    ResultRow(title: "総数", value: fmtTrim(total), unit: "錠", color: .orange)
                    if s > 0 {
                        let b = sheetBreakdown(total: total, size: s)
                        ResultRow(title: "シート換算", value: "\(b.sheets)シート＋\(fmtTrim(b.rest))錠", big: false)
                    }
                }
            }
        }
        .calcForm("不均等投与")
    }
}

import SwiftUI

struct PowderView: View {
    enum Mode: String, CaseIterable { case toGram = "成分量 → 製剤量", toMg = "製剤量 → 成分量" }
    enum ConcUnit: String, CaseIterable { case percent = "%", mgPerG = "mg/g" }

    @State private var mode = Mode.toGram
    @State private var conc = "10"
    @State private var concUnit = ConcUnit.percent
    @State private var amount = ""
    @State private var times = "3"
    @State private var days = ""

    var body: some View {
        Form {
            Section {
                Picker("計算", selection: $mode) {
                    ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                HStack {
                    NumField(title: "製剤の濃度", text: $conc)
                    Picker("", selection: $concUnit) {
                        ForEach(ConcUnit.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .labelsHidden()
                    .fixedSize()
                }
                QuickButtons(values: [1, 2, 5, 10, 20, 50], text: $conc, suffix: concUnit.rawValue)
            } footer: {
                Text("10% = 100mg/g。力価表示の製剤は力価の値を入れてください。")
            }
            Section {
                if mode == .toGram {
                    NumField(title: "1日の成分量", text: $amount, unit: "mg")
                } else {
                    NumField(title: "1日の製剤量", text: $amount, unit: "g")
                }
                NumField(title: "1日の回数", text: $times, unit: "回", integer: true)
                NumField(title: "日数（任意）", text: $days, unit: "日", integer: true)
            }

            if let c = parseNum(conc), c > 0, let a = parseNum(amount), a > 0 {
                let mgPerG = concUnit == .percent ? c * 10 : c
                let gram = mode == .toGram ? a / mgPerG : a
                let mg = mode == .toGram ? a : a * mgPerG
                Section("結果") {
                    ResultRow(title: "製剤の濃度", value: fmtTrim(mgPerG, 3), unit: "mg/g", big: false)
                    if mode == .toGram {
                        ResultRow(title: "1日の製剤量", value: fmtTrim(gram, 3), unit: "g", color: .orange)
                    } else {
                        ResultRow(title: "1日の成分量", value: fmtTrim(mg, 2), unit: "mg", color: .orange)
                    }
                    if let t = parseInt(times), t > 0 {
                        ResultRow(title: "1回（1包）", value: fmtTrim(gram / Double(t), 3), unit: "g",
                                  note: "成分量 \(fmtTrim(mg / Double(t), 2))mg")
                    }
                    if let n = parseInt(days), n > 0 {
                        ResultRow(title: "総量（\(n)日分）", value: fmtTrim(gram * Double(n), 3), unit: "g")
                    }
                }
            }
        }
        .calcForm("散剤の換算")
    }
}

struct PediatricView: View {
    @EnvironmentObject var p: PatientStore
    @State private var adult = ""
    @State private var unit = "mg"

    /// von Harnack の換算表（年齢の下限, 成人量に対する比, 表示）
    private let harnack: [(Double, Double, String)] = [
        (0, 0, "新生児（表の対象外）"),
        (0.25, 1.0 / 6, "3か月 1/6"),
        (0.5, 1.0 / 5, "6か月 1/5"),
        (1, 1.0 / 4, "1歳 1/4"),
        (3, 1.0 / 3, "3歳 1/3"),
        (7.5, 1.0 / 2, "7.5歳 1/2"),
        (12, 2.0 / 3, "12歳 2/3"),
        (20, 1, "成人 1"),
    ]

    var body: some View {
        Form {
            Section {
                NumField(title: "年齢", text: $p.age, unit: "歳")
                HStack {
                    NumField(title: "成人量", text: $adult)
                    TextField("単位", text: $unit)
                        .frame(width: 52)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text("年齢は小数でも入れられます（例：1歳6か月 → 1.5）。年齢は患者情報と共通です。")
            }
            if let age = p.ageV, age >= 0, let a = parseNum(adult), a > 0 {
                Section {
                    if age >= 1 {
                        let r = (age * 4 + 20) / 100
                        ResultRow(title: "Augsberger-II式", value: fmtTrim(a * min(r, 1), 2), unit: unit,
                                  note: "成人量 ×（年齢×4＋20）÷100 ＝ ×\(fmt(min(r, 1), 2))")
                    } else {
                        MissingRow("Augsberger-II式は1歳以上が対象です")
                    }
                    let h = harnack.last(where: { age >= $0.0 }) ?? harnack[0]
                    if h.1 > 0 {
                        ResultRow(title: "von Harnack", value: fmtTrim(a * h.1, 2), unit: unit,
                                  note: "表の \(h.2) を適用（表の年齢の間は目安）")
                    }
                    if age < 12 {
                        let y = age / (age + 12)
                        ResultRow(title: "Young式", value: fmtTrim(a * y, 2), unit: unit,
                                  note: "成人量 × 年齢 ÷（年齢＋12）＝ ×\(fmt(y, 2))")
                    }
                } header: {
                    Text("小児の量（目安）")
                } footer: {
                    Text("あくまで目安です。添付文書や小児の用量の資料で必ず確認してください。")
                }
            }
        }
        .calcForm("小児薬用量")
    }
}

struct WeightDoseView: View {
    @EnvironmentObject var p: PatientStore
    @State private var perKg = ""
    @State private var times = "3"
    @State private var maxDaily = ""
    @State private var perDose = false

    var body: some View {
        Form {
            Section {
                NumField(title: "体重", text: $p.weight, unit: "kg")
                Picker("用量の単位", selection: $perDose) {
                    Text("mg/kg/日").tag(false)
                    Text("mg/kg/回").tag(true)
                }
                .pickerStyle(.segmented)
                NumField(title: "用量", text: $perKg, unit: perDose ? "mg/kg/回" : "mg/kg/日")
                NumField(title: "1日の回数", text: $times, unit: "回", integer: true)
                NumField(title: "1日の上限（任意）", text: $maxDaily, unit: "mg")
            }
            if let w = p.weightV, w > 0, let k = parseNum(perKg), k > 0, let t = parseInt(times), t > 0 {
                let raw = perDose ? k * w * Double(t) : k * w
                let cap = parseNum(maxDaily)
                let daily = cap.map { min(raw, $0) } ?? raw
                Section("結果") {
                    ResultRow(title: "1日量", value: fmtTrim(daily, 2), unit: "mg", color: .orange)
                    ResultRow(title: "1回量", value: fmtTrim(daily / Double(t), 2), unit: "mg")
                    if let cap, raw > cap {
                        WarningRow("計算値 \(fmtTrim(raw, 2))mg が上限 \(fmtTrim(cap, 2))mg を超えたため、上限で表示しています")
                    }
                }
            }
        }
        .calcForm("体重あたりの用量")
    }
}

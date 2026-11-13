import SwiftUI

struct DripView: View {
    @State private var volume = ""
    @State private var hours = ""
    @State private var minutes = ""
    @State private var factor = 20

    var body: some View {
        Form {
            Section {
                NumField(title: "輸液量", text: $volume, unit: "mL")
                QuickButtons(values: [50, 100, 250, 500, 1000], text: $volume, suffix: "mL")
                NumField(title: "時間", text: $hours, unit: "時間")
                NumField(title: "＋", text: $minutes, unit: "分", integer: true)
                Picker("輸液セット", selection: $factor) {
                    Text("成人用 20滴/mL").tag(20)
                    Text("小児用 60滴/mL").tag(60)
                }
            }
            let totalMin = (parseNum(hours) ?? 0) * 60 + (parseNum(minutes) ?? 0)
            if let v = parseNum(volume), v > 0, totalMin > 0 {
                let mlh = v / totalMin * 60
                let dpm = v * Double(factor) / totalMin
                Section("結果") {
                    ResultRow(title: "流量", value: fmt(mlh, 1), unit: "mL/h")
                    ResultRow(title: "滴下数", value: fmt(dpm, 1), unit: "滴/分", color: .orange)
                    ResultRow(title: "10秒あたり", value: fmt(dpm / 6, 1), unit: "滴")
                    ResultRow(title: "1滴の間隔", value: fmt(60 / dpm, 1), unit: "秒")
                }
            }
        }
        .calcForm("点滴の滴下速度")
    }
}

struct GammaView: View {
    @EnvironmentObject var p: PatientStore
    @State private var drugMg = ""
    @State private var volume = ""
    @State private var input = ""
    @State private var fromGamma = true

    var body: some View {
        Form {
            Section {
                NumField(title: "薬の量", text: $drugMg, unit: "mg")
                NumField(title: "溶液の全量", text: $volume, unit: "mL")
                NumField(title: "体重", text: $p.weight, unit: "kg")
            } footer: {
                Text("例：ドパミン 150mg/50mL（3mg/mL）")
            }
            Section {
                Picker("計算", selection: $fromGamma) {
                    Text("γ → mL/h").tag(true)
                    Text("mL/h → γ").tag(false)
                }
                .pickerStyle(.segmented)
                NumField(title: fromGamma ? "投与速度" : "流量", text: $input,
                         unit: fromGamma ? "μg/kg/min" : "mL/h")
            }
            if let mg = parseNum(drugMg), mg > 0, let v = parseNum(volume), v > 0,
               let w = p.weightV, w > 0 {
                let conc = mg * 1000 / v  // μg/mL
                Section("結果") {
                    ResultRow(title: "濃度", value: fmtTrim(conc, 1), unit: "μg/mL", big: false)
                    ResultRow(title: "1 mL/h は", value: fmt(conc / (w * 60), 3), unit: "γ", big: false)
                    if let x = parseNum(input), x > 0 {
                        if fromGamma {
                            ResultRow(title: "流量", value: fmt(x * w * 60 / conc, 2), unit: "mL/h", color: .orange)
                        } else {
                            ResultRow(title: "投与速度", value: fmt(x * conc / (w * 60), 2), unit: "μg/kg/min", color: .orange)
                        }
                    }
                }
            } else {
                Section { MissingRow("薬の量・溶液量・体重を入力してください") }
            }
        }
        .calcForm("γ計算")
    }
}

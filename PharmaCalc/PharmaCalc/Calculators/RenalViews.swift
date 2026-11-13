import SwiftUI

struct RenalView: View {
    @EnvironmentObject var p: PatientStore
    @State private var roundUp = false

    var body: some View {
        Form {
            Section {
                PatientFields(fields: [.age, .sex, .height, .weight, .scr, .cys])
            } header: {
                Text("患者情報（共通）")
            }

            if let age = p.ageV, age < 18 {
                Section {
                    WarningRow("18歳未満は成人の式の対象外です。小児用の式を使ってください。")
                }
            }

            ccrSection
            egfrCrSection
            egfrCysSection
        }
        .calcForm("腎機能")
    }

    private var bsa: Double? {
        guard let w = p.weightV, let h = p.heightV, w > 0, h > 0 else { return nil }
        return BodyCalc.bsaDuBois(weight: w, height: h)
    }

    @ViewBuilder private var ccrSection: some View {
        Section {
            Toggle("Crが0.6未満なら0.6として計算", isOn: $roundUp)
            if let age = p.ageV, let w = p.weightV, let cr0 = p.scrV, cr0 > 0, w > 0 {
                let cr = roundUp ? max(cr0, 0.6) : cr0
                let actual = Renal.cockcroftGault(age: age, weight: w, scr: cr, sex: p.sex)
                ResultRow(title: "実体重 \(fmt(w))kg", value: fmt(actual), unit: "mL/min")
                if let h = p.heightV, h > 0 {
                    let ibw = BodyCalc.idealWeight(height: h, sex: p.sex)
                    if ibw > 0 {
                        ResultRow(title: "理想体重 \(fmt(ibw))kg",
                                  value: fmt(Renal.cockcroftGault(age: age, weight: ibw, scr: cr, sex: p.sex)),
                                  unit: "mL/min",
                                  note: w < ibw ? "実体重が理想体重より少ないため、実体重の値を使うのが一般的です" : nil)
                        if w > ibw {
                            let abw = BodyCalc.adjustedWeight(actual: w, ideal: ibw)
                            ResultRow(title: "補正体重 \(fmt(abw))kg",
                                      value: fmt(Renal.cockcroftGault(age: age, weight: abw, scr: cr, sex: p.sex)),
                                      unit: "mL/min",
                                      note: w > ibw * 1.2 ? "実体重が理想体重の120%を超えています。補正体重での値を考慮してください" : nil)
                        }
                    }
                }
            } else {
                MissingRow("年齢・体重・血清Crを入力してください")
            }
        } header: {
            Text("CCr（Cockcroft-Gault式）")
        } footer: {
            Text("CCr = (140−年齢)×体重 ÷ (72×血清Cr)、女性は×0.85\n理想体重（Devine）：男性 50＋0.91×(身長−152.4)、女性 45.5＋0.91×(身長−152.4)\n補正体重 = 理想体重＋0.4×(実体重−理想体重)")
        }
    }

    @ViewBuilder private var egfrCrSection: some View {
        Section {
            if let age = p.ageV, let cr = p.scrV, cr > 0, age > 0 {
                let e = Renal.egfrCr(age: age, scr: cr, sex: p.sex)
                ResultRow(title: "eGFR", value: fmt(e), unit: "mL/min/1.73m²", note: Renal.stage(e))
                if let bsa {
                    ResultRow(title: "体表面積未補正（BSA \(fmt(bsa, 2))m²）",
                              value: fmt(Renal.deindex(e, bsa: bsa)), unit: "mL/min",
                              note: "薬の投与量の判断にはこちらを使います")
                } else {
                    MissingRow("身長・体重を入れると、体表面積で補正しない値（mL/min）も出します")
                }
            } else {
                MissingRow("年齢・血清Crを入力してください")
            }
        } header: {
            Text("eGFR（血清Cr・日本人の式）")
        } footer: {
            Text("eGFR = 194 × Cr^−1.094 × 年齢^−0.287、女性は×0.739\n未補正値 = eGFR × 体表面積(DuBois) ÷ 1.73")
        }
    }

    @ViewBuilder private var egfrCysSection: some View {
        Section {
            if let age = p.ageV, let c = p.cysV, c > 0, age > 0 {
                let e = Renal.egfrCys(age: age, cys: c, sex: p.sex)
                ResultRow(title: "eGFRcys", value: fmt(e), unit: "mL/min/1.73m²", note: Renal.stage(e))
                if let bsa {
                    ResultRow(title: "体表面積未補正", value: fmt(Renal.deindex(e, bsa: bsa)), unit: "mL/min")
                }
            } else {
                MissingRow("年齢・シスタチンCを入力してください")
            }
        } header: {
            Text("eGFR（シスタチンC）")
        } footer: {
            Text("eGFRcys = 104 × CysC^−1.019 × 0.996^年齢（女性は×0.929）− 8\n筋肉量が極端に少ない・多い人では血清Crより正確なことがあります")
        }
    }
}

struct BodyView: View {
    @EnvironmentObject var p: PatientStore

    var body: some View {
        Form {
            Section("患者情報（共通）") {
                PatientFields(fields: [.sex, .height, .weight])
            }
            if let w = p.weightV, let h = p.heightV, w > 0, h > 0 {
                Section {
                    ResultRow(title: "DuBois式", value: fmt(BodyCalc.bsaDuBois(weight: w, height: h), 3), unit: "m²")
                    ResultRow(title: "藤本式", value: fmt(BodyCalc.bsaFujimoto(weight: w, height: h), 3), unit: "m²")
                    ResultRow(title: "Mosteller式", value: fmt(BodyCalc.bsaMosteller(weight: w, height: h), 3), unit: "m²")
                } header: {
                    Text("体表面積（BSA）")
                } footer: {
                    Text("DuBois：0.007184 × W^0.425 × H^0.725\n藤本：0.008883 × W^0.444 × H^0.663\nMosteller：√(H×W÷3600)")
                }
                Section("体格") {
                    let b = BodyCalc.bmi(weight: w, height: h)
                    ResultRow(title: "BMI", value: fmt(b), note: BodyCalc.bmiCategory(b) + "（日本肥満学会の基準）")
                    ResultRow(title: "標準体重（BMI 22）", value: fmt(BodyCalc.standardWeight(height: h)), unit: "kg")
                    let ibw = BodyCalc.idealWeight(height: h, sex: p.sex)
                    ResultRow(title: "理想体重（Devine）", value: fmt(ibw), unit: "kg")
                    if w > ibw {
                        ResultRow(title: "補正体重", value: fmt(BodyCalc.adjustedWeight(actual: w, ideal: ibw)), unit: "kg")
                    }
                }
            } else {
                Section { MissingRow("身長・体重を入力してください") }
            }
        }
        .calcForm("体表面積・体格")
    }
}

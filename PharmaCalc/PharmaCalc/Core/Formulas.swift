import Foundation

/// 腎機能・体格の計算式（画面から切り離した純粋な関数）
enum Renal {
    /// Cockcroft-Gault式 CCr（mL/min）
    static func cockcroftGault(age: Double, weight: Double, scr: Double, sex: Sex) -> Double {
        let v = (140 - age) * weight / (72 * scr)
        return sex == .female ? v * 0.85 : v
    }

    /// 日本人のGFR推算式（血清Cr）mL/min/1.73m²
    static func egfrCr(age: Double, scr: Double, sex: Sex) -> Double {
        let v = 194 * pow(scr, -1.094) * pow(age, -0.287)
        return sex == .female ? v * 0.739 : v
    }

    /// 日本人のGFR推算式（シスタチンC）mL/min/1.73m²
    static func egfrCys(age: Double, cys: Double, sex: Sex) -> Double {
        var v = 104 * pow(cys, -1.019) * pow(0.996, age)
        if sex == .female { v *= 0.929 }
        return v - 8
    }

    /// 体表面積で補正しない値（mL/min）にする
    static func deindex(_ egfr: Double, bsa: Double) -> Double { egfr * bsa / 1.73 }

    /// CKDのGFR区分
    static func stage(_ gfr: Double) -> String {
        switch gfr {
        case 90...: return "G1（正常または高値）"
        case 60..<90: return "G2（正常または軽度低下）"
        case 45..<60: return "G3a（軽度〜中等度低下）"
        case 30..<45: return "G3b（中等度〜高度低下）"
        case 15..<30: return "G4（高度低下）"
        default: return "G5（末期腎不全）"
        }
    }
}

enum BodyCalc {
    /// 理想体重（Devine式）kg
    static func idealWeight(height: Double, sex: Sex) -> Double {
        let base = sex == .male ? 50.0 : 45.5
        return base + 0.91 * (height - 152.4)
    }

    /// 補正体重 kg
    static func adjustedWeight(actual: Double, ideal: Double) -> Double {
        ideal + 0.4 * (actual - ideal)
    }

    static func bmi(weight: Double, height: Double) -> Double {
        let m = height / 100
        return weight / (m * m)
    }

    /// 標準体重（BMI 22）
    static func standardWeight(height: Double) -> Double {
        let m = height / 100
        return 22 * m * m
    }

    static func bsaDuBois(weight: Double, height: Double) -> Double {
        0.007184 * pow(weight, 0.425) * pow(height, 0.725)
    }

    static func bsaFujimoto(weight: Double, height: Double) -> Double {
        0.008883 * pow(weight, 0.444) * pow(height, 0.663)
    }

    static func bsaMosteller(weight: Double, height: Double) -> Double {
        (weight * height / 3600).squareRoot()
    }

    static func bmiCategory(_ b: Double) -> String {
        switch b {
        case ..<18.5: return "低体重"
        case 18.5..<25: return "普通体重"
        case 25..<30: return "肥満（1度）"
        case 30..<35: return "肥満（2度）"
        case 35..<40: return "肥満（3度）"
        default: return "肥満（4度）"
        }
    }
}

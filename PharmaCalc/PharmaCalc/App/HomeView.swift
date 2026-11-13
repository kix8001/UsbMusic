import SwiftUI

/// 計算の分類
enum CalcGroup: String, CaseIterable {
    case renal = "腎機能・体格"
    case days = "日数・日付"
    case quantity = "数量・残薬"
    case dose = "用量"
    case injection = "注射・点滴"
}

/// 電卓の一覧（ホーム画面・検索・お気に入りで共通）
enum CalcItem: String, CaseIterable, Identifiable {
    case renal, body
    case endDate, nextVisit, validity, cycle, age
    case remaining, sheet, uneven
    case powder, pediatric, weightDose
    case drip, gamma

    var id: String { rawValue }

    var title: String {
        switch self {
        case .renal: return "腎機能（CCr・eGFR）"
        case .body: return "体表面積・BMI・理想体重"
        case .endDate: return "服用終了日"
        case .nextVisit: return "次回受診までの日数"
        case .validity: return "処方箋の有効期限"
        case .cycle: return "休薬期間のある処方"
        case .age: return "年齢・月齢"
        case .remaining: return "残薬調整"
        case .sheet: return "PTPシート換算"
        case .uneven: return "不均等投与の総数"
        case .powder: return "散剤の換算（mg ⇄ g）"
        case .pediatric: return "小児薬用量"
        case .weightDose: return "体重あたりの用量"
        case .drip: return "点滴の滴下速度"
        case .gamma: return "γ計算（μg/kg/min）"
        }
    }

    var subtitle: String {
        switch self {
        case .renal: return "Cockcroft-Gault・日本人eGFR・シスタチンC"
        case .body: return "DuBois・藤本・Mosteller"
        case .endDate: return "開始日＋処方日数 → 最終服用日"
        case .nextVisit: return "受診日までに必要な日数・錠数"
        case .validity: return "交付日を含めて4日"
        case .cycle: return "3週投与1週休薬など"
        case .age: return "生年月日から年齢・月齢・日齢"
        case .remaining: return "残薬を差し引いた処方日数・数量"
        case .sheet: return "錠数 → シート＋バラ"
        case .uneven: return "朝2・夕1など"
        case .powder: return "成分量 ⇄ 製剤量、1包あたり"
        case .pediatric: return "Augsberger・von Harnack・Young"
        case .weightDose: return "mg/kg/日 → 1回量"
        case .drip: return "mL/h・滴/分"
        case .gamma: return "γ ⇄ mL/h"
        }
    }

    var keywords: String {
        switch self {
        case .renal: return "ccr egfr クレアチニン 腎 シスタチン"
        case .body: return "bsa bmi ibw 体重 身長"
        case .endDate, .nextVisit: return "日数 日付 カレンダー"
        case .validity: return "処方せん 期限 4日"
        case .cycle: return "休薬 クール 抗がん剤"
        case .age: return "誕生日 月齢"
        case .remaining: return "残薬 余り 調整"
        case .sheet: return "ptp シート バラ"
        case .uneven: return "朝 昼 夕 寝る前"
        case .powder: return "散 力価 % 包"
        case .pediatric: return "小児 子供 こども"
        case .weightDose: return "mg/kg"
        case .drip: return "輸液 滴下"
        case .gamma: return "ガンマ 持続 シリンジ"
        }
    }

    var icon: String {
        switch self {
        case .renal: return "drop"
        case .body: return "figure.stand"
        case .endDate: return "calendar"
        case .nextVisit: return "calendar.badge.clock"
        case .validity: return "doc.text"
        case .cycle: return "arrow.triangle.2.circlepath"
        case .age: return "birthday.cake"
        case .remaining: return "minus.forwardslash.plus"
        case .sheet: return "square.grid.3x3"
        case .uneven: return "sunrise"
        case .powder: return "scalemass"
        case .pediatric: return "figure.and.child.holdinghands"
        case .weightDose: return "person.badge.plus"
        case .drip: return "drop.triangle"
        case .gamma: return "syringe"
        }
    }

    var group: CalcGroup {
        switch self {
        case .renal, .body: return .renal
        case .endDate, .nextVisit, .validity, .cycle, .age: return .days
        case .remaining, .sheet, .uneven: return .quantity
        case .powder, .pediatric, .weightDose: return .dose
        case .drip, .gamma: return .injection
        }
    }

    @ViewBuilder var destination: some View {
        switch self {
        case .renal: RenalView()
        case .body: BodyView()
        case .endDate: EndDateView()
        case .nextVisit: NextVisitView()
        case .validity: ValidityView()
        case .cycle: CycleView()
        case .age: AgeView()
        case .remaining: RemainingView()
        case .sheet: SheetView()
        case .uneven: UnevenView()
        case .powder: PowderView()
        case .pediatric: PediatricView()
        case .weightDose: WeightDoseView()
        case .drip: DripView()
        case .gamma: GammaView()
        }
    }
}

struct HomeView: View {
    @State private var query = ""
    @AppStorage("favorites") private var favRaw = ""

    private var favs: [CalcItem] {
        favRaw.split(separator: ",").compactMap { CalcItem(rawValue: String($0)) }
    }

    private var filtered: [CalcItem] {
        let q = query.lowercased()
        return CalcItem.allCases.filter {
            ($0.title + $0.subtitle + $0.keywords).lowercased().contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    Section {
                        NavigationLink { PatientView() } label: { PatientSummaryRow() }
                    } header: {
                        Text("患者情報（全計算で共通）")
                    }
                    if !favs.isEmpty {
                        Section("よく使う") {
                            ForEach(favs) { row($0) }
                        }
                    }
                    ForEach(CalcGroup.allCases, id: \.self) { g in
                        Section(g.rawValue) {
                            ForEach(CalcItem.allCases.filter { $0.group == g }) { row($0) }
                        }
                    }
                } else {
                    ForEach(filtered) { row($0) }
                }
            }
            .navigationTitle("薬剤師電卓")
            .searchable(text: $query, prompt: "計算を探す")
        }
    }

    private func toggleFav(_ c: CalcItem) {
        var f = favs
        if let i = f.firstIndex(of: c) { f.remove(at: i) } else { f.append(c) }
        favRaw = f.map(\.rawValue).joined(separator: ",")
    }

    private func row(_ c: CalcItem) -> some View {
        let isFav = favs.contains(c)
        return NavigationLink {
            c.destination
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.title)
                    Text(c.subtitle).font(.caption).foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: c.icon).foregroundStyle(.tint)
            }
        }
        .swipeActions(edge: .trailing) {
            Button {
                toggleFav(c)
            } label: {
                Label(isFav ? "外す" : "よく使う", systemImage: isFav ? "star.slash" : "star")
            }
            .tint(.yellow)
        }
        .contextMenu {
            Button {
                toggleFav(c)
            } label: {
                Label(isFav ? "よく使うから外す" : "よく使うに追加", systemImage: isFav ? "star.slash" : "star")
            }
        }
    }
}

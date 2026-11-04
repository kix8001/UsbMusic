import SwiftUI
import UIKit

struct ReportView: View {
    @EnvironmentObject var store: Store
    @State private var year = Calendar.current.component(.year, from: Date())
    @State private var month = Calendar.current.component(.month, from: Date())
    @State private var editSettings = false
    @State private var shareItem: ShareItem?
    @State private var error: String?

    var body: some View {
        let r = MonthReport(year: year, month: month, trips: store.trips, settings: store.settings)
        NavigationStack {
            List {
                Section {
                    HStack {
                        Button { shift(-1) } label: { Image(systemName: "chevron.left") }
                        Spacer()
                        Text(r.title).font(.title2.weight(.semibold))
                        Spacer()
                        Button { shift(1) } label: { Image(systemName: "chevron.right") }
                    }
                    .buttonStyle(.borderless)
                }
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("支給額").foregroundStyle(.secondary)
                        Text(Yen.format(r.totalAmount)).font(.system(size: 40, weight: .bold)).monospacedDigit()
                    }
                    ForEach([Category.commute, .business]) { c in
                        AmountRow(category: c,
                                  detail: "\(r.count(c))回 \(Fmt.km(r.km(c))) km × \(MonthReport.fmtRate(store.settings.rate(c)))円",
                                  amount: Yen.format(r.amount(c)))
                    }
                    AmountRow(category: .personal,
                              detail: "\(r.count(.personal))回 \(Fmt.km(r.km(.personal))) km", amount: "対象外")
                    if r.count(.unset) > 0 {
                        AmountRow(category: .unset,
                                  detail: "\(r.count(.unset))回 \(Fmt.km(r.km(.unset))) km", amount: "未計上")
                    }
                } footer: {
                    if r.count(.unset) > 0 {
                        Text("未分類の走行が \(r.count(.unset)) 件あります。履歴で「未分類」を選び、通勤・業務・私用に分けてください。")
                            .foregroundStyle(.red)
                    }
                }
                Section {
                    Button {
                        do { shareItem = ShareItem(url: try r.pdfFile()) } catch { self.error = error.localizedDescription }
                    } label: { Label("PDF を共有", systemImage: "doc.richtext") }
                    Button {
                        do { shareItem = ShareItem(url: try r.csvFile()) } catch { self.error = error.localizedDescription }
                    } label: { Label("CSV を共有", systemImage: "tablecells") }
                } header: {
                    Text("会社に出すレポート（通勤・業務の走行のみ）")
                } footer: {
                    Text("メール・LINE・ファイルなど、共有先を選べます。CSV は Excel でそのまま開けます。")
                }
                if !r.claimTrips.isEmpty {
                    Section("明細") {
                        ForEach(r.claimTrips) { t in
                            HStack {
                                CategoryBadge(category: t.category)
                                VStack(alignment: .leading) {
                                    Text(Fmt.day(t.start)).font(.subheadline)
                                    let what = [t.destination, t.purpose].filter { !$0.isEmpty }.joined(separator: " ・ ")
                                    if !what.isEmpty {
                                        Text(what).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                }
                                Spacer()
                                VStack(alignment: .trailing) {
                                    Text("\(Fmt.km(t.reportKm)) km").font(.subheadline)
                                    Text(Yen.format(r.tripAmount(t))).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("レポート")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { editSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .sheet(isPresented: $editSettings) { SettingsSheet() }
            .sheet(item: $shareItem) { item in ShareSheet(items: [item.url]) }
            .alert("レポートを作れませんでした", isPresented: Binding(
                get: { error != nil }, set: { if !$0 { error = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(error ?? "")
            }
        }
    }

    private func shift(_ d: Int) {
        let m = year * 12 + (month - 1) + d
        year = m / 12
        month = m % 12 + 1
    }
}

private struct AmountRow: View {
    let category: Category
    let detail: String
    let amount: String

    var body: some View {
        HStack {
            CategoryBadge(category: category)
            Text(detail).font(.subheadline)
            Spacer()
            Text(amount).font(.headline).monospacedDigit()
        }
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

/// iOS 標準の共有画面
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

private struct SettingsSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var commute = ""
    @State private var business = ""

    var body: some View {
        let c = Fmt.parseKm(commute)
        let b = Fmt.parseKm(business)
        NavigationStack {
            Form {
                Section("氏名（レポートに載せる）") {
                    TextField("氏名", text: $name)
                }
                Section {
                    HStack {
                        Text("通勤")
                        TextField("18", text: $commute).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        Text("円/km")
                    }
                    HStack {
                        Text("業務")
                        TextField("36", text: $business).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        Text("円/km")
                    }
                } header: {
                    Text("1km あたりの支給額")
                } footer: {
                    Text("単価を変えると、過去の月の金額も新しい単価で計算し直されます。")
                }
            }
            .navigationTitle("単価・氏名の設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var s = store.settings
                        s.name = name.trimmingCharacters(in: .whitespaces)
                        s.commuteRate = c ?? s.commuteRate
                        s.businessRate = b ?? s.businessRate
                        store.settings = s
                        dismiss()
                    }
                    .disabled(c == nil || b == nil)
                }
            }
            .onAppear {
                name = store.settings.name
                commute = MonthReport.fmtRate(store.settings.commuteRate)
                business = MonthReport.fmtRate(store.settings.businessRate)
            }
        }
    }
}

import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var store: Store
    @State private var editing: Trip?
    @State private var adding = false

    var body: some View {
        let cal = Calendar.current
        let now = Date()
        let year = cal.component(.year, from: now)
        let month = cal.monthKey(now)
        let monthKm = store.trips.filter { cal.monthKey($0.start) == month }.reduce(0) { $0 + $1.km }
        let yearKm = store.trips.filter { cal.component(.year, from: $0.start) == year }.reduce(0) { $0 + $1.km }
        let totalKm = store.trips.reduce(0) { $0 + $1.km }
        let groups = Dictionary(grouping: store.trips) { cal.monthKey($0.start) }
            .sorted { $0.key > $1.key }

        NavigationStack {
            List {
                Section {
                    HStack(spacing: 8) {
                        SummaryCard(label: "今月", km: monthKm)
                        SummaryCard(label: "\(String(year))年", km: yearKm)
                        SummaryCard(label: "累計", km: totalKm)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                if store.trips.isEmpty {
                    Text("まだ記録がありません").foregroundStyle(.secondary)
                }
                ForEach(groups, id: \.key) { g in
                    Section {
                        ForEach(g.value) { t in
                            Button { editing = t } label: { TripRow(trip: t) }
                                .buttonStyle(.plain)
                        }
                        .onDelete { idx in
                            idx.map { g.value[$0].id }.forEach { store.deleteTrip($0) }
                        }
                    } header: {
                        HStack {
                            Text(Fmt.month(g.value[0].start))
                            Spacer()
                            Text("\(Fmt.km(g.value.reduce(0) { $0 + $1.km })) km ・ \(g.value.count)回")
                        }
                    }
                }
            }
            .navigationTitle("履歴")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    ShareLink(item: store.csv(), subject: Text("走行距離ログ")) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editing) { t in TripEditSheet(trip: t) }
            .sheet(isPresented: $adding) { ManualTripSheet() }
        }
    }
}

private struct TripRow: View {
    let trip: Trip

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(Fmt.day(trip.start))
                Text(subtitle).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Text("\(Fmt.km(trip.km)) km").font(.title3.weight(.semibold)).monospacedDigit()
        }
        .contentShape(Rectangle())
    }

    private var subtitle: String {
        var s = trip.manual ? "手入力" : "\(Fmt.time(trip.start))〜\(Fmt.time(trip.end))"
        if !trip.note.isEmpty { s += " ・ \(trip.note)" }
        return s
    }
}

private struct TripEditSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let trip: Trip
    @State private var km = ""
    @State private var note = ""
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(Fmt.full(trip.start))
                    if !trip.manual {
                        Text("\(Fmt.time(trip.start))〜\(Fmt.time(trip.end))（\(Fmt.duration(trip.end.timeIntervalSince(trip.start)))）")
                            .foregroundStyle(.secondary)
                    }
                }
                Section("距離 (km)") {
                    TextField("距離", text: $km).keyboardType(.decimalPad)
                }
                Section("メモ（行き先など）") {
                    TextField("メモ", text: $note)
                }
                Section {
                    Button("この記録を削除", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle("走行の編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var t = trip
                        t.meters = (Fmt.parseKm(km) ?? trip.km) * 1000
                        t.note = note.trimmingCharacters(in: .whitespaces)
                        store.updateTrip(t)
                        dismiss()
                    }
                    .disabled(Fmt.parseKm(km) == nil)
                }
            }
            .confirmationDialog("この記録を削除しますか？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("削除", role: .destructive) {
                    store.deleteTrip(trip.id)
                    dismiss()
                }
            }
            .onAppear {
                km = String(format: "%.2f", trip.km)
                note = trip.note
            }
        }
    }
}

private struct ManualTripSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var day = Date()
    @State private var km = ""
    @State private var note = ""

    var body: some View {
        let v = Fmt.parseKm(km)
        NavigationStack {
            Form {
                DatePicker("日付", selection: $day, displayedComponents: .date)
                    .environment(\.locale, Locale(identifier: "ja_JP"))
                Section("距離 (km)") {
                    TextField("例：12.5", text: $km).keyboardType(.decimalPad)
                }
                Section("メモ（行き先など）") {
                    TextField("メモ", text: $note)
                }
            }
            .navigationTitle("走行を手入力")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("追加") {
                        // 時刻は正午にしておく（日付の境目で別の日に入らないように）
                        let noon = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: day) ?? day
                        store.addTrip(start: noon, end: noon, meters: (v ?? 0) * 1000,
                                      note: note.trimmingCharacters(in: .whitespaces), manual: true)
                        dismiss()
                    }
                    .disabled(v == nil || v == 0)
                }
            }
        }
    }
}

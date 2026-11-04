import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var store: Store
    @State private var editing: Trip?
    @State private var adding = false
    /// nil = すべて
    @State private var filter: Category?

    var body: some View {
        let cal = Calendar.current
        let now = Date()
        let year = cal.component(.year, from: now)
        let month = cal.monthKey(now)
        let claim = store.trips.filter { $0.category.isClaim }
        let monthKm = claim.filter { cal.monthKey($0.start) == month }.reduce(0) { $0 + $1.km }
        let yearKm = claim.filter { cal.component(.year, from: $0.start) == year }.reduce(0) { $0 + $1.km }
        let unset = store.trips.filter { $0.category == .unset }.count
        let shown = filter.map { f in store.trips.filter { $0.category == f } } ?? store.trips
        let groups = Dictionary(grouping: shown) { cal.monthKey($0.start) }
            .sorted { $0.key > $1.key }

        NavigationStack {
            List {
                Section {
                    HStack(spacing: 8) {
                        SummaryCard(label: "今月（通勤＋業務）", km: monthKm)
                        SummaryCard(label: "\(String(year))年（通勤＋業務）", km: yearKm)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            FilterChip(title: "すべて", on: filter == nil) { filter = nil }
                            ForEach([Category.unset, .commute, .business, .personal]) { c in
                                FilterChip(title: c == .unset && unset > 0 ? "\(c.label)（\(unset)）" : c.label,
                                           on: filter == c) { filter = c }
                            }
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 0, trailing: 0))
                    .listRowBackground(Color.clear)
                }
                if shown.isEmpty {
                    Text(store.trips.isEmpty ? "まだ記録がありません" : "該当する記録はありません")
                        .foregroundStyle(.secondary)
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
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editing) { t in TripEditSheet(trip: t, isNew: false) }
            .sheet(isPresented: $adding) {
                TripEditSheet(
                    trip: Trip(start: Date(), end: Date(), meters: 0, manual: true,
                               category: store.settings.nextCategory),
                    isNew: true
                )
            }
        }
    }
}

private struct FilterChip: View {
    let title: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(on ? Color.accentColor : Color(.secondarySystemBackground)))
                .foregroundStyle(on ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

private struct TripRow: View {
    let trip: Trip

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    CategoryBadge(category: trip.category)
                    Text(Fmt.day(trip.start))
                    Text(trip.manual ? "手入力" : "\(Fmt.time(trip.start))〜\(Fmt.time(trip.end))")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                let what = [trip.destination, trip.purpose, trip.note].filter { !$0.isEmpty }.joined(separator: " ・ ")
                if !what.isEmpty {
                    Text(what).font(.subheadline).lineLimit(1)
                }
                if !trip.startAddr.isEmpty || !trip.endAddr.isEmpty {
                    Text("\(trip.startAddr.isEmpty ? "?" : trip.startAddr) → \(trip.endAddr.isEmpty ? "?" : trip.endAddr)")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            Text("\(Fmt.km(trip.km)) km").font(.title3.weight(.semibold)).monospacedDigit()
        }
        .contentShape(Rectangle())
    }
}

/// 走行の編集と手入力（isNew = true）を兼ねる
private struct TripEditSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let trip: Trip
    let isNew: Bool
    @State private var t: Trip
    @State private var km = ""
    @State private var confirmDelete = false

    init(trip: Trip, isNew: Bool) {
        self.trip = trip
        self.isNew = isNew
        _t = State(initialValue: trip)
        _km = State(initialValue: isNew ? "" : String(format: "%.2f", trip.km))
    }

    var body: some View {
        let v = Fmt.parseKm(km)
        NavigationStack {
            Form {
                Section {
                    if isNew {
                        DatePicker("日付", selection: $t.start, displayedComponents: .date)
                            .environment(\.locale, Locale(identifier: "ja_JP"))
                    } else {
                        Text(Fmt.full(trip.start))
                        if !trip.manual {
                            Text("\(Fmt.time(trip.start))〜\(Fmt.time(trip.end))（\(Fmt.duration(trip.end.timeIntervalSince(trip.start)))）")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Section("区分") {
                    CategoryPicker(selection: $t.category)
                }
                Section("距離 (km)") {
                    TextField("例：12.5", text: $km).keyboardType(.decimalPad)
                }
                Section("行き先・用件") {
                    TextField("行き先（訪問先など）", text: $t.destination)
                    TextField("用件", text: $t.purpose)
                }
                Section("経路") {
                    TextField("出発地", text: $t.startAddr)
                    TextField("到着地", text: $t.endAddr)
                }
                Section("メモ") {
                    TextField("メモ", text: $t.note)
                }
                if !isNew {
                    Section {
                        Button("この記録を削除", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(isNew ? "走行を手入力" : "走行の編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isNew ? "追加" : "保存") {
                        var out = t
                        out.meters = (v ?? 0) * 1000
                        for k in [\Trip.destination, \Trip.purpose, \Trip.startAddr, \Trip.endAddr, \Trip.note] {
                            out[keyPath: k] = out[keyPath: k].trimmingCharacters(in: .whitespaces)
                        }
                        if isNew {
                            // 時刻は正午にしておく（日付の境目で別の日に入らないように）
                            let noon = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: t.start) ?? t.start
                            out.start = noon
                            out.end = noon
                            store.addTrip(out)
                        } else {
                            store.updateTrip(out)
                        }
                        dismiss()
                    }
                    .disabled(v == nil || (isNew && v == 0))
                }
            }
            .confirmationDialog("この記録を削除しますか？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("削除", role: .destructive) {
                    store.deleteTrip(trip.id)
                    dismiss()
                }
            }
        }
    }
}

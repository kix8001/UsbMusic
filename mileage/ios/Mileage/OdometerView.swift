import SwiftUI

/// 車のメーター（オドメーター）の値を手で記録する画面。前回からの差＝実際に走った距離
struct OdometerView: View {
    @EnvironmentObject var store: Store
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("給油のときなどに車のメーターの値を記録すると、前回からの走行距離がわかります。")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let newest = store.odos.first, let oldest = store.odos.last, store.odos.count >= 2 {
                        // 同じ期間に GPS で測った距離と比べる（GPS の誤差の目安になる）
                        let gps = store.trips
                            .filter { $0.start >= oldest.time && $0.start <= newest.time }
                            .reduce(0) { $0 + $1.km }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(Fmt.day(oldest.time)) 〜 \(Fmt.day(newest.time))")
                                .font(.footnote).foregroundStyle(.secondary)
                            Text("メーター：\(Fmt.km(newest.km - oldest.km)) km").font(.title3.weight(.semibold))
                            Text("同じ期間の GPS 記録：\(Fmt.km(gps)) km").foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    if store.odos.isEmpty {
                        Text("右上の＋で記録します").foregroundStyle(.secondary)
                    }
                    ForEach(Array(store.odos.enumerated()), id: \.element.id) { i, o in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(Fmt.km(o.km)) km").font(.title3.weight(.semibold)).monospacedDigit()
                                Text(Fmt.full(o.time) + (o.note.isEmpty ? "" : " ・ \(o.note)"))
                                    .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            if i + 1 < store.odos.count {
                                Text("+\(Fmt.km(o.km - store.odos[i + 1].km)) km").foregroundStyle(.green)
                            }
                        }
                    }
                    .onDelete { idx in
                        idx.map { store.odos[$0].id }.forEach { store.deleteOdo($0) }
                    }
                }
            }
            .navigationTitle("メーター")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $adding) { OdoAddSheet() }
        }
    }
}

private struct OdoAddSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var km = ""
    @State private var note = ""

    var body: some View {
        let v = Fmt.parseKm(km)
        let last = store.odos.first
        NavigationStack {
            Form {
                Section {
                    TextField("メーターの値", text: $km).keyboardType(.decimalPad)
                } header: {
                    Text("メーターの値 (km)")
                } footer: {
                    if let last {
                        if let v, v < last.km {
                            Text("前回より小さい値です").foregroundStyle(.red)
                        } else {
                            Text("前回：\(Fmt.km(last.km)) km（\(Fmt.day(last.time))）")
                        }
                    }
                }
                Section("メモ（給油など）") {
                    TextField("メモ", text: $note)
                }
            }
            .navigationTitle("メーターを記録")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("記録") {
                        store.addOdo(time: Date(), km: v ?? 0, note: note.trimmingCharacters(in: .whitespaces))
                        dismiss()
                    }
                    .disabled(v == nil)
                }
            }
        }
    }
}

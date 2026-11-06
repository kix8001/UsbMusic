import SwiftUI

struct TrackView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var tracker: Tracker
    @State private var confirmStop = false
    @State private var editSettings = false

    var body: some View {
        let cal = Calendar.current
        let live = tracker.running ? tracker.meters / 1000 : 0
        let todayKm = store.trips.filter { cal.isDateInToday($0.start) }.reduce(0) { $0 + $1.km } + live
        let month = cal.monthKey(Date())
        let monthKm = store.trips.filter { cal.monthKey($0.start) == month }.reduce(0) { $0 + $1.km } + live

        VStack(spacing: 8) {
            HStack {
                Spacer()
                Button { editSettings = true } label: {
                    Image(systemName: "gearshape").font(.title3)
                }
            }
            Text(tracker.running ? "計測中" : "停止中")
                .font(.headline)
                .foregroundStyle(tracker.running ? Color.green : Color.secondary)
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(Fmt.km2(live))
                    .font(.system(size: 72, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text("km").font(.title2)
            }
            .minimumScaleFactor(0.5)
            .lineLimit(1)

            Group {
                if tracker.running {
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Text(Fmt.duration(ctx.date.timeIntervalSince(tracker.startTime)))
                            .font(.title3).monospacedDigit()
                    }
                    if tracker.hasFix {
                        Text(String(format: "%.0f km/h ・ GPS精度 ±%.0fm", tracker.speedKmh, tracker.accuracy ?? 0))
                            .foregroundStyle(.secondary)
                    } else {
                        Text("GPS を探しています…").foregroundStyle(.red)
                    }
                } else {
                    Text(" ").font(.title3)
                    Text(" ")
                }
            }

            Spacer()

            Text(tracker.running ? "この走行の区分" : "次の走行の区分")
                .font(.footnote).foregroundStyle(.secondary)
            CategoryPicker(selection: $store.settings.nextCategory)
                .padding(.bottom, 12)

            Button {
                if tracker.running {
                    confirmStop = true
                } else {
                    tracker.requestNotificationPermission()
                    tracker.start()
                }
            } label: {
                Text(tracker.running ? "停止" : "開始")
                    .font(.system(size: 36, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 180, height: 180)
                    .background(Circle().fill(tracker.running ? Color.red : Color.blue))
            }
            .alert("運転を終了しましたか？", isPresented: Binding(
                get: { tracker.running && tracker.askEnd }, set: { _ in }
            )) {
                Button("終了して保存") { tracker.endAtStop() }
                Button("まだ運転中", role: .cancel) { tracker.continueDriving() }
            } message: {
                Text("\(Fmt.time(tracker.stoppedAt)) から止まっています。答えがなければ \(Fmt.time(tracker.autoSaveAt)) に、\(Fmt.time(tracker.stoppedAt)) に終了したものとして自動で保存します。")
            }

            Spacer()

            HStack(spacing: 12) {
                SummaryCard(label: "今日", km: todayKm)
                SummaryCard(label: "今月", km: monthKm)
            }
        }
        .padding(20)
        .sheet(isPresented: $editSettings) { SettingsSheet() }
        .confirmationDialog(
            "計測を終了しますか？",
            isPresented: $confirmStop,
            titleVisibility: .visible
        ) {
            Button("終了して保存") { tracker.stop() }
            Button("続ける", role: .cancel) {}
        } message: {
            Text("この走行（\(Fmt.km2(tracker.meters / 1000)) km・\(store.settings.nextCategory.label)）を履歴に保存します。区分や行き先は履歴であとから直せます。50m 未満の場合は保存しません。")
        }
        .alert("位置情報の許可が必要です", isPresented: $tracker.permissionDenied) {
            Button("設定を開く") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("閉じる", role: .cancel) {}
        } message: {
            Text("走行距離を GPS で測るため、設定アプリで位置情報を「使用中のみ」以上、「正確な位置情報」をオンにしてください。")
        }
    }
}

struct SummaryCard: View {
    let label: String
    let km: Double

    var body: some View {
        VStack(spacing: 4) {
            Text(label).font(.subheadline).foregroundStyle(.secondary)
            Text("\(Fmt.km(km)) km").font(.title3.weight(.semibold)).monospacedDigit()
                .minimumScaleFactor(0.6).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

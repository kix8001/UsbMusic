import SwiftUI

@main
struct MileageApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var tracker = Tracker.shared

    var body: some Scene {
        WindowGroup {
            TabView {
                TrackView()
                    .tabItem { Label("計測", systemImage: "car.fill") }
                HistoryView()
                    .tabItem { Label("履歴", systemImage: "list.bullet") }
                OdometerView()
                    .tabItem { Label("メーター", systemImage: "gauge") }
            }
            .environmentObject(store)
            .environmentObject(tracker)
        }
    }
}

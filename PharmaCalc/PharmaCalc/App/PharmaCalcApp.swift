import SwiftUI

@main
struct PharmaCalcApp: App {
    @StateObject private var patient = PatientStore()
    @StateObject private var history = ScanHistory()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(patient)
                .environmentObject(history)
                .environment(\.locale, Locale(identifier: "ja_JP"))
                .tint(.teal)
        }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("計算", systemImage: "function") }
            ScanView()
                .tabItem { Label("バーコード", systemImage: "barcode.viewfinder") }
            HistoryView()
                .tabItem { Label("履歴", systemImage: "clock") }
            AboutView()
                .tabItem { Label("情報", systemImage: "info.circle") }
        }
    }
}

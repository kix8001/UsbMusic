import AVFoundation
import SwiftUI

struct ScanView: View {
    @EnvironmentObject var history: ScanHistory
    @AppStorage("autoOpen") private var autoOpen = true
    @State private var torch = false
    @State private var result: GS1Result?
    @State private var web: WebPage?
    @State private var manual = ""
    @State private var auth = AVCaptureDevice.authorizationStatus(for: .video)
    @FocusState private var manualFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                camera
                    .frame(maxWidth: .infinity)
                    .frame(height: 300)
                    .clipped()
                Form {
                    if let r = result { resultSection(r) }
                    Section {
                        Toggle("読み取ったらすぐ添付文書を開く", isOn: $autoOpen)
                    } footer: {
                        Text("PTPシートのGS1データバー、箱のGS1-128・JANコードに対応しています。PTPのバーコードは小さいので、10cmほど離して画面いっぱいに写すと読みやすくなります。")
                    }
                    Section("手入力") {
                        HStack {
                            TextField("GTIN（14桁）またはJAN（13桁）", text: $manual)
                                .keyboardType(.numberPad)
                                .focused($manualFocused)
                            Button("開く") {
                                manualFocused = false
                                handle(manual)
                            }
                            .disabled(manual.isEmpty)
                        }
                        Link(destination: PMDA.searchURL) {
                            Label("PMDAで薬品名から探す", systemImage: "magnifyingglass")
                        }
                    }
                }
            }
            .navigationTitle("バーコード")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        torch.toggle()
                    } label: {
                        Image(systemName: torch ? "flashlight.on.fill" : "flashlight.off.fill")
                    }
                }
            }
            .sheet(item: $web) { page in
                SafariView(url: page.url).ignoresSafeArea()
            }
            .onAppear {
                if auth == .notDetermined {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        DispatchQueue.main.async {
                            auth = AVCaptureDevice.authorizationStatus(for: .video)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private var camera: some View {
        switch auth {
        case .authorized:
            ZStack {
                BarcodeScanner(torch: torch, paused: web != nil) { handle($0) }
                RoundedRectangle(cornerRadius: 12)
                    .stroke(.white.opacity(0.85), lineWidth: 2)
                    .frame(width: 260, height: 110)
                    .allowsHitTesting(false)
            }
        case .notDetermined:
            Color.black.overlay(ProgressView().tint(.white))
        default:
            Color.black.overlay(
                VStack(spacing: 12) {
                    Text("カメラの使用が許可されていません").foregroundStyle(.white)
                    Button("設定を開く") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
            )
        }
    }

    @ViewBuilder private func resultSection(_ r: GS1Result) -> some View {
        Section("読み取り結果") {
            if let gtin = r.gtin {
                let memo = history.memo(for: gtin)
                if !memo.isEmpty {
                    Text(memo).font(.headline)
                }
                LabeledContent("GTIN", value: gtin)
                if let e = r.expiry { LabeledContent("有効期限", value: e) }
                if let l = r.lot { LabeledContent("ロット", value: l) }
                if let q = r.quantity { LabeledContent("数量", value: q) }
                Button {
                    if let url = PMDA.url(gtin: gtin) { web = WebPage(url: url) }
                } label: {
                    Label("添付文書を開く", systemImage: "doc.text.magnifyingglass")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            } else {
                WarningRow("医薬品のコード（GTIN）が見つかりませんでした")
                Text(r.raw).font(.caption.monospaced()).textSelection(.enabled)
            }
        }
    }

    private func handle(_ code: String) {
        let r = GS1.parse(code)
        withAnimation { result = r }
        guard let gtin = r.gtin else { return }
        history.add(r)
        if autoOpen, web == nil, let url = PMDA.url(gtin: gtin) {
            web = WebPage(url: url)
        }
    }
}

struct HistoryView: View {
    @EnvironmentObject var history: ScanHistory
    @State private var web: WebPage?
    @State private var query = ""

    private var filtered: [ScanRecord] {
        guard !query.isEmpty else { return history.items }
        return history.items.filter { $0.memo.localizedCaseInsensitiveContains(query) || $0.gtin.contains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                if history.items.isEmpty {
                    Text("バーコードを読み取ると、ここに履歴が残ります")
                        .foregroundStyle(.secondary)
                }
                ForEach(filtered) { item in
                    NavigationLink {
                        HistoryDetailView(id: item.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.memo.isEmpty ? "名称未登録" : item.memo)
                                .foregroundStyle(item.memo.isEmpty ? .secondary : .primary)
                            Text(item.gtin).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            Text(item.date.formatted(date: .abbreviated, time: .shortened))
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions(edge: .leading) {
                        Button {
                            if let url = PMDA.url(gtin: item.gtin) { web = WebPage(url: url) }
                        } label: {
                            Label("添付文書", systemImage: "doc.text")
                        }
                        .tint(.teal)
                    }
                }
                .onDelete { idx in
                    let ids = idx.map { filtered[$0].id }
                    history.items.removeAll { ids.contains($0.id) }
                }
            }
            .navigationTitle("履歴")
            .searchable(text: $query, prompt: "薬品名・GTIN")
            .sheet(item: $web) { page in
                SafariView(url: page.url).ignoresSafeArea()
            }
        }
    }
}

struct HistoryDetailView: View {
    @EnvironmentObject var history: ScanHistory
    let id: UUID
    @State private var web: WebPage?

    var body: some View {
        if let i = history.items.firstIndex(where: { $0.id == id }) {
            let item = history.items[i]
            Form {
                Section {
                    TextField("薬品名（メモ）", text: $history.items[i].memo)
                } footer: {
                    Text("薬品名を入れておくと、次に読み取ったときにも表示されます")
                }
                Section {
                    LabeledContent("GTIN", value: item.gtin)
                    if let e = item.expiry { LabeledContent("有効期限", value: e) }
                    if let l = item.lot { LabeledContent("ロット", value: l) }
                    LabeledContent("読み取り日時", value: item.date.formatted(date: .numeric, time: .shortened))
                }
                Section {
                    Button {
                        if let url = PMDA.url(gtin: item.gtin) { web = WebPage(url: url) }
                    } label: {
                        Label("添付文書を開く", systemImage: "doc.text.magnifyingglass")
                    }
                }
            }
            .navigationTitle("履歴の詳細")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $web) { page in
                SafariView(url: page.url).ignoresSafeArea()
            }
        } else {
            Text("削除されました").foregroundStyle(.secondary)
        }
    }
}

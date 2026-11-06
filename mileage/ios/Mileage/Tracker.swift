import CoreLocation
import Foundation
import UserNotifications

/// GPS で走行距離を測る。バックグラウンドでも計測を続ける（Info.plist の UIBackgroundModes: location）
final class Tracker: NSObject, ObservableObject {
    static let shared = Tracker()

    @Published private(set) var running = false
    @Published private(set) var startTime = Date()
    @Published private(set) var meters: Double = 0
    @Published private(set) var speedKmh: Double = 0
    @Published private(set) var accuracy: Double?
    @Published private(set) var hasFix = false
    @Published var permissionDenied = false
    /// 止まったままなので「運転を終了しましたか？」と聞いているところ
    @Published private(set) var askEnd = false
    /// 最後に動いた時刻（終了するときはこの時刻で保存する）
    @Published private(set) var stoppedAt = Date()
    /// 答えがなければこの時刻に自動で保存する
    @Published private(set) var autoSaveAt = Date()

    /// この距離（m）以上動いたら「動いている」とみなす（止まっているときの GPS のゆらぎより大きく）
    private let moveRadius = 50.0
    /// GPS が切れる直前にこの速度（km/h）以上で走っていたら、トンネルとみなして止まった扱いにしない
    private let tunnelSpeed = 20.0
    /// この時間（秒）位置が取れなければ GPS が切れたとみなす
    private let fixLostSeconds = 20.0
    private var anchor: CLLocation?
    private var lastMoveTime = Date()
    private var lastFixAt = Date.distantPast
    private var lastFixSpeed = 0.0
    /// 「まだ運転中」と答えたので、次に動き出すまで聞かない
    private var snoozed = false
    private var timer: Timer?

    /// これより悪い精度（m）の位置は使わない
    private let maxAccuracy = 25.0
    /// これを超える速度（m/s、約250km/h）になる位置は飛びとみなして捨てる
    private let maxSpeed = 70.0
    /// これより短い移動は止まっているときの GPS のゆらぎとみなして足さない
    private let minStep = 8.0
    /// これ未満の走行は保存しない（誤操作対策）
    static let minSaveMeters = 50.0

    private let manager = CLLocationManager()
    private var last: CLLocation?
    /// 出発地（最初に取れた良い位置）と、最後に取れた良い位置。住所の自動入力に使う
    private var firstLoc: CLLocationCoordinate2D?
    private var lastLoc: CLLocationCoordinate2D?
    private var pendingStart = false
    private var lastPersist = Date.distantPast
    private let defaults = UserDefaults.standard

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        // 止まっていても位置を受け取り続ける（止まったことの判定に使う）
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        setUpNotifications()
        // 計測中にアプリが終了されていたら、続きから再開する
        let saved = defaults.double(forKey: "active.start")
        if saved > 0 {
            if defaults.object(forKey: "active.firstLat") != nil {
                firstLoc = CLLocationCoordinate2D(latitude: defaults.double(forKey: "active.firstLat"),
                                                  longitude: defaults.double(forKey: "active.firstLon"))
            }
            begin(start: Date(timeIntervalSince1970: saved), meters: defaults.double(forKey: "active.meters"))
        }
    }

    func start() {
        guard !running else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            pendingStart = true
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            permissionDenied = true
        default:
            let now = Date()
            defaults.set(now.timeIntervalSince1970, forKey: "active.start")
            defaults.set(0.0, forKey: "active.meters")
            defaults.removeObject(forKey: "active.firstLat")
            defaults.removeObject(forKey: "active.firstLon")
            firstLoc = nil
            lastLoc = nil
            begin(start: now, meters: 0)
        }
    }

    private func begin(start: Date, meters: Double) {
        startTime = start
        self.meters = meters
        last = nil
        hasFix = false
        running = true
        lastMoveTime = Date()
        anchor = nil
        snoozed = false
        askEnd = false
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            self?.checkStopped()
        }
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    /// 計測を終えて保存する。end は終了時刻（止まった時刻で終える場合はその時刻）
    func stop(end: Date = Date(), auto: Bool = false) {
        guard running else { return }
        timer?.invalidate()
        timer = nil
        clearAsk()
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        if meters >= Tracker.minSaveMeters {
            Store.shared.addTrip(Trip(
                start: startTime, end: max(startTime, end), meters: meters,
                category: Store.shared.settings.nextCategory,
                startLat: firstLoc?.latitude, startLon: firstLoc?.longitude,
                endLat: lastLoc?.latitude, endLon: lastLoc?.longitude
            ))
            Store.shared.fillMissingAddresses()
            if auto { notifySaved(meters: meters, end: end) }
        }
        for k in ["active.start", "active.meters", "active.firstLat", "active.firstLon"] {
            defaults.removeObject(forKey: k)
        }
        firstLoc = nil
        lastLoc = nil
        running = false
        meters = 0
        speedKmh = 0
        accuracy = nil
        hasFix = false
        last = nil
    }

    private func handle(_ loc: CLLocation) {
        guard running else { return }
        if loc.speed >= 0 { speedKmh = loc.speed * 3.6 }
        accuracy = loc.horizontalAccuracy
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= maxAccuracy else {
            hasFix = false
            return
        }
        hasFix = true
        lastFixAt = Date()
        lastFixSpeed = max(0, loc.speed * 3.6)
        if anchor == nil || loc.distance(from: anchor!) >= moveRadius {
            // 動いた：止まった判定をやり直す
            anchor = loc
            lastMoveTime = Date()
            snoozed = false
            if askEnd { clearAsk() }
        }
        if firstLoc == nil {
            firstLoc = loc.coordinate
            defaults.set(loc.coordinate.latitude, forKey: "active.firstLat")
            defaults.set(loc.coordinate.longitude, forKey: "active.firstLon")
        }
        lastLoc = loc.coordinate
        guard let prev = last else {
            last = loc
            return
        }
        let d = loc.distance(from: prev)
        let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
        if dt <= 0 { return }
        if d / dt > maxSpeed { return } // 位置が飛んだ：前の点のまま次を待つ
        if d >= max(minStep, loc.horizontalAccuracy) {
            meters += d
            last = loc
        }
        // 短い移動はためておき、しきい値を超えたらまとめて足す
        if Date().timeIntervalSince(lastPersist) > 5 {
            lastPersist = Date()
            defaults.set(meters, forKey: "active.meters")
        }
    }
}

// MARK: - 止まったことの判定と「運転を終了しましたか？」

extension Tracker: UNUserNotificationCenterDelegate {
    private static let askId = "ask-end"
    private static let savedId = "auto-saved"

    private func setUpNotifications() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let end = UNNotificationAction(identifier: "END", title: "終了して保存", options: [])
        let cont = UNNotificationAction(identifier: "CONTINUE", title: "まだ運転中", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "ASK_END", actions: [end, cont], intentIdentifiers: [], options: [])
        ])
    }

    /// 計測を始めるときに通知の許可を求める（初回だけ確認が出る）
    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// 止まったままか調べ、設定の時間を過ぎたら聞く。答えがなければ自動で保存する
    private func checkStopped() {
        guard running else { return }
        let settings = Store.shared.settings
        let now = Date()
        // トンネルで GPS が切れているだけなら、止まった扱いにしない
        let fixLost = now.timeIntervalSince(lastFixAt) > fixLostSeconds
        if fixLost && lastFixSpeed >= tunnelSpeed && !askEnd {
            lastMoveTime = now
            return
        }
        if askEnd {
            if now >= autoSaveAt { stop(end: stoppedAt, auto: true) }
            return
        }
        guard settings.stopAlertMinutes > 0, !snoozed else { return }
        if now.timeIntervalSince(lastMoveTime) >= Double(settings.stopAlertMinutes * 60) {
            stoppedAt = lastMoveTime
            autoSaveAt = now.addingTimeInterval(Double(settings.autoSaveMinutes * 60))
            askEnd = true
            let c = UNMutableNotificationContent()
            c.title = "運転を終了しましたか？"
            c.body = "\(Fmt.time(stoppedAt)) から止まっています。答えがなければ \(Fmt.time(autoSaveAt)) に自動で保存します。"
            c.sound = .default
            c.categoryIdentifier = "ASK_END"
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: Tracker.askId, content: c, trigger: nil))
        }
    }

    /// 「終了して保存」：止まった時刻で終了する
    func endAtStop() {
        stop(end: askEnd ? stoppedAt : Date())
    }

    /// 「まだ運転中」：次に動き出すまでは聞かない
    func continueDriving() {
        guard running else { return }
        snoozed = true
        clearAsk()
    }

    private func clearAsk() {
        askEnd = false
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Tracker.askId])
        center.removeDeliveredNotifications(withIdentifiers: [Tracker.askId])
    }

    private func notifySaved(meters: Double, end: Date) {
        let c = UNMutableNotificationContent()
        c.title = "走行を自動で保存しました"
        c.body = String(format: "%.1f km（%@ に終了）", meters / 1000, Fmt.time(end))
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: Tracker.savedId, content: c, trigger: nil))
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let action = response.actionIdentifier
        DispatchQueue.main.async {
            if action == "END" { self.endAtStop() }
            if action == "CONTINUE" { self.continueDriving() }
            completionHandler()
        }
    }

    // アプリを開いているときは、確認は画面のダイアログで出すので通知は出さない
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if notification.request.identifier == Tracker.askId {
            completionHandler([])
        } else {
            completionHandler([.banner, .sound])
        }
    }
}

extension Tracker: CLLocationManagerDelegate {
    // CLLocationManager はメインスレッドで作ったので、通知もメインスレッドに来る
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for loc in locations { handle(loc) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard pendingStart else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            pendingStart = false
            start()
        case .denied, .restricted:
            pendingStart = false
            permissionDenied = true
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        hasFix = false
    }
}
